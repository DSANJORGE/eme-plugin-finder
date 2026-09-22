package org.entermediadb.ai.llm.openai;

import java.nio.charset.StandardCharsets;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.llm.BaseLlmConnection;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.CatalogEnabled;
import org.openedit.Data;
import org.openedit.OpenEditException;
import org.openedit.data.BaseData;
import org.openedit.page.Page;
import org.openedit.util.JSONParser;
import org.openedit.util.OutputFiller;

public class OpenAiConnection extends BaseLlmConnection implements CatalogEnabled, LlmConnection
{
	private static Log log = LogFactory.getLog(OpenAiConnection.class);

	@Override
	public String getLlmProtocol()
	{
		return "openai";
	}

	// Keys only llama.cpp understands. OpenAI-compatible hosts such as Groq answer 400 "property ... is unsupported"
	// and Anthropic "Extra inputs are not permitted"; LlamaOpenAiConnection reports protocol "llama" and keeps them,
	// so one call template serves every provider.
	protected static final String[] LLAMA_ONLY_KEYS = { "chat_template_kwargs", "cache_prompt", "slot_id", "id_slot", "n_probs", "min_keep" };

	public JSONObject prepareRequest(JSONObject inPayload)
	{
		if (!"llama".equals(getLlmProtocol()))
		{
			// enable_thinking:false is llama.cpp's "do not reason before answering"; reasoning_effort is the
			// OpenAI-compatible spelling. Not cosmetic on groq's free tier (8000 tokens/minute), where reasoning
			// ate three quarters of a classify batch's completion tokens (2026-09-20).
			Object kwargs = inPayload.get("chat_template_kwargs");
			if (kwargs instanceof JSONObject && Boolean.FALSE.equals(((JSONObject) kwargs).get("enable_thinking")) && inPayload.get("reasoning_effort") == null)
			{
				inPayload.put("reasoning_effort", "low");
			}
			for (String key : LLAMA_ONLY_KEYS)
			{
				inPayload.remove(key);
			}
		}
		return mergeExtraParams(inPayload);
	}

	/** Renders /{mediadb}/ai/{protocol}/calls/{function}.json (catalog fallback) and prepares it. */
	public JSONObject loadCallPayload(AgentContext inContext, String inFunction)
	{
		MediaArchive archive = getMediaArchive();
		inContext.put("model", getModelName());
		inContext.addContext("aiserver", keylessAiServerData());

		if (inContext.getContextValue("jsonfilename") != null)
		{
			inFunction = (String) inContext.getContextValue("jsonfilename");
		}

		String templatepath = "/" + archive.getMediaDbId() + "/ai/" + getLlmProtocol() + "/calls/" + inFunction + ".json";
		Page template = archive.getPageManager().getPage(templatepath);
		if (!template.exists())
		{
			templatepath = "/" + archive.getCatalogId() + "/ai/" + getLlmProtocol() + "/calls/" + inFunction + ".json";
			template = archive.getPageManager().getPage(templatepath);
		}
		if (!template.exists())
		{
			throw new OpenEditException("Requested Function Does Not Exist in MediaDB or Catalog:" + inFunction);
		}

		String definition = loadInputFromTemplate(inContext, templatepath);
		JSONObject payload = (JSONObject) new JSONParser().parse(definition);
		return prepareRequest(payload);
	}

	/** The aiserver row as templates may see it: same values, minus the API key. */
	protected Data keylessAiServerData()
	{
		Data server = getAiServerData();
		if (server == null)
		{
			return null;
		}
		BaseData copy = new BaseData();
		copy.setId(server.getId());
		copy.setProperties(server.getProperties());
		copy.getProperties().remove("serverapikey");
		return copy;
	}

	/**
	 * One chat/completions round trip. A 429 is retried twice, two seconds apart (groq rate-limits bursts; two
	 * learners asking at once is enough). Any other non-200 is salvaged when possible, else raised with the
	 * status and the first 500 chars of the body.
	 */
	protected LlmResponse chat(JSONObject inPayload)
	{
		log.info("Sent: " + inPayload.toJSONString());
		HttpPost method = new HttpPost(getServerRoot() + "/chat/completions");
		method.addHeader("Authorization", "Bearer " + getApiKey());
		method.setHeader("Content-Type", "application/json");
		applyLlmHeaders(method, getSharedHeaders());
		method.setEntity(new StringEntity(inPayload.toJSONString(), StandardCharsets.UTF_8));

		CloseableHttpResponse resp = execute(method);
		for (int tries = 0; tries < 2 && resp.getStatusLine().getStatusCode() == 429; tries++)
		{
			getConnection().release(resp);
			log.info("Rate limited by " + getServerRoot() + ", retrying");
			try
			{
				Thread.sleep(2000);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				break;
			}
			resp = execute(method);
		}
		try
		{
			int status = resp.getStatusLine().getStatusCode();
			if (status != 200)
			{
				String body = "";
				try
				{
					body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
				}
				catch (Exception ignore)
				{
				}
				JSONObject salvaged = salvageStructure(body, inPayload);
				if (salvaged != null)
				{
					log.info("Salvaged: " + salvaged.toJSONString());
					LlmResponse salvage = createResponse();
					salvage.setRawResponse(salvaged);
					return salvage;
				}
				if (body.length() > 500)
				{
					body = body.substring(0, 500);
				}
				throw new OpenEditException("LLM HTTP " + status + " from " + getServerRoot() + ": " + body);
			}
			JSONObject json = (JSONObject) getConnection().parseMap(resp);
			log.info("Returned: " + json.toJSONString());
			LlmResponse response = createResponse();
			response.setRawResponse(json);
			return response;
		}
		finally
		{
			getConnection().release(resp);
		}
	}

	/**
	 * The answer inside a groq `json_validate_failed` error body, reshaped as a normal reply: a reasoning model
	 * (gpt-oss-120b) often writes a good reply and then fails to wrap it in the schema, and groq answers 400 with
	 * the prose in `failed_generation`. Only a schema with exactly one required property can be filled this way;
	 * anything else returns null and the caller raises as before.
	 */
	protected JSONObject salvageStructure(String inBody, JSONObject inRequest)
	{
		try
		{
			JSONObject body = (JSONObject) new JSONParser().parse(inBody);
			JSONObject error = body == null ? null : (JSONObject) body.get("error");
			if (error == null || !"json_validate_failed".equals(error.get("code")))
			{
				return null;
			}
			String text = (String) error.get("failed_generation");
			if (text == null || text.trim().isEmpty())
			{
				return null;
			}
			JSONObject format = (JSONObject) inRequest.get("response_format");
			JSONObject schema = format == null ? null : (JSONObject) format.get("json_schema");
			schema = schema == null ? null : (JSONObject) schema.get("schema");
			JSONArray required = schema == null ? null : (JSONArray) schema.get("required");
			if (required == null || required.size() != 1)
			{
				return null;
			}
			JSONObject content = new JSONObject();
			content.put(String.valueOf(required.get(0)), text.trim());
			JSONObject message = new JSONObject();
			message.put("role", "assistant");
			message.put("content", content.toJSONString());
			JSONObject choice = new JSONObject();
			choice.put("index", Long.valueOf(0));
			choice.put("message", message);
			choice.put("finish_reason", "stop");
			JSONArray choices = new JSONArray();
			choices.add(choice);
			JSONObject out = new JSONObject();
			out.put("choices", choices);
			return out;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	public LlmResponse runPageAsInput(AgentContext agentcontext, String inTemplate)
	{
		agentcontext.addContext("mediaarchive", getMediaArchive());

		String input = loadInputFromTemplate(agentcontext, inTemplate);
		log.info(inTemplate + " process chat");
		String endpoint = getServerRoot();

		HttpPost method = new HttpPost(endpoint);
		method.addHeader("Authorization", "Bearer " + getApiKey());
		method.setHeader("Content-Type", "application/json");

		method.setEntity(new StringEntity(input, "UTF-8"));

		CloseableHttpResponse resp = execute(method);

		JSONObject json = getConnection().parseMap(resp);

		LlmResponse response = (LlmResponse) createResponse();
		response.setRawResponse(json);

		/*
		 * String nextFunction = response.getRunFunctionName(); if (nextFunction != null) {
		 * agentcontext.setFunctionName(nextFunction); }
		 */

		// getMediaArchive().saveData("agentcontext", agentcontext);
		return response;

	}

	public LlmResponse createImage(String inPrompt)
	{
		return createImage(inPrompt, 1, "1024x1024");
	}

	public LlmResponse createImage(String inPrompt, int imagecount, String inSize)
	{
		if (getApiKey() == null)
		{
			throw new OpenEditException("No API key configured for OpenAI image creation");
		}

		if (inPrompt == null)
		{
			throw new OpenEditException("No prompt given for image creation");
		}

		JSONObject payload = new JSONObject();
		payload.put("model", getModelName());
		payload.put("prompt", inPrompt);
		payload.put("n", imagecount);
		payload.put("size", inSize);
		if (!"gpt-image-1".equals(getModelName()))
		{
			payload.put("response_format", "b64_json");
		}
		else
		{
			payload.put("moderation", "low");
		}

		// String endpoint = "https://api.openai.com/v1/images/generations";
		// String endpoint = "http://localhost:3000/generations"; // for local testing

		log.info("Creating image with prompt: " + inPrompt + "  Model: " + getModelName());
		LlmResponse res = callJson("/images/generations", payload);
		return res;

	}

	public OutputFiller getFiller()
	{
		return filler;
	}

	public void setFiller(OutputFiller inFiller)
	{
		filler = inFiller;
	}

	@Override
	public LlmResponse callCreateFunction(AgentContext context, String inFunction)
	{
		MediaArchive archive = getMediaArchive();

		JSONObject obj = new JSONObject();
		obj.put("model", getModelName());

		String contentPath = "/" + archive.getMediaDbId() + "/ai/" + getLlmProtocol() + "/createdialog/systemmessage/" + inFunction + ".html";
		boolean contentExists = archive.getPageManager().getPage(contentPath).exists();
		if (!contentExists)
		{
			contentPath = "/" + archive.getCatalogId() + "/ai/" + getLlmProtocol() + "/createdialog/systemmessage/" + inFunction + ".html";
			contentExists = archive.getPageManager().getPage(contentPath).exists();
		}
		if (!contentExists)
		{
			throw new OpenEditException("Requested Content Does Not Exist in MediaDB or Catalog:" + inFunction);
		}

		String content = loadInputFromTemplate(context, contentPath);

		JSONArray messages = new JSONArray();
		JSONObject message = new JSONObject();
		message.put("role", "user");
		message.put("content", content);
		messages.add(message);

		obj.put("messages", messages);

		// Handle function call definition
		if (inFunction != null)
		{
			String functionPath = "/" + archive.getMediaDbId() + "/ai/" + getLlmProtocol() + "/createdialog/functions/" + inFunction + ".json";
			boolean functionExists = archive.getPageManager().getPage(functionPath).exists();
			if (!functionExists)
			{
				functionPath = "/" + archive.getCatalogId() + "/ai/" + getLlmProtocol() + "/createdialog/functions/" + inFunction + ".json";
				functionExists = archive.getPageManager().getPage(functionPath).exists();
			}
			if (!functionExists)
			{
				throw new OpenEditException("Requested Function Does Not Exist in MediaDB or Catalog:" + inFunction);
			}

			String definition = loadInputFromTemplate(context, functionPath);

			JSONParser parser = new JSONParser();
			JSONObject functionDef = (JSONObject) parser.parse(definition);

			JSONArray functions = new JSONArray();
			functions.add(functionDef);

			JSONArray tools = new JSONArray();
			JSONObject toolfunction = new JSONObject();

			toolfunction.put("function", functionDef);
			toolfunction.put("type", "function");
			tools.add(toolfunction);

			obj.put("tools", tools);

			JSONObject toolchoice = new JSONObject();
			toolchoice.put("type", "function");
			JSONObject functionname = new JSONObject();
			functionname.put("name", inFunction);
			toolchoice.put("function", functionname);
			obj.put("tool_choice", toolchoice);

		}

		log.info("Call Function: " + obj.toJSONString());

		LlmResponse res = chat(prepareRequest(obj));
		return res;

	}

	@Override
	public LlmResponse callClassifyFunction(AgentContext params, String inFunction, String inBase64Image)
	{
		return callClassifyFunction(params, inFunction, inBase64Image, null);
	}

	public LlmResponse callClassifyFunction(AgentContext inAgentContext, String inFunction, String inBase64Image, String textContent)
	{
		if (textContent != null)
		{
			inAgentContext.put("textcontent", textContent);
		}
		JSONObject payload = loadCallPayload(inAgentContext, inFunction);
		attachImageMessage(payload, inBase64Image);
		return chat(payload);
	}

	public LlmResponse callToolsFunction(AgentContext params, String inFunction)
	{
		return chat(loadCallPayload(params, inFunction));
	}

	public JSONObject attachImageMessage(JSONObject payload, String inBase64Image)
	{
		if (inBase64Image != null && !inBase64Image.isEmpty())
		{
			JSONArray messages = (JSONArray) payload.get("messages");

			JSONObject message = new JSONObject();
			message.put("role", "user");

			JSONArray contentArray = new JSONArray();

			JSONObject imageContent = new JSONObject();
			imageContent.put("type", "image_url");
			JSONObject imageUrl = new JSONObject();
			imageUrl.put("url", inBase64Image);
			imageContent.put("image_url", imageUrl);

			contentArray.add(imageContent);

			message.put("content", contentArray);

			messages.add(message);
		}

		return payload;
	}

	@Override
	public LlmResponse callStructure(AgentContext inParams, String inFunctionName)
	{
		return chat(loadCallPayload(inParams, inFunctionName));
	}

	@Override
	public LlmResponse callSmartCreatorAiAction(AgentContext inParams, String inActionName)
	{
		inParams.put("model", getModelName());

		String templatepath = "/" + getMediaArchive().getMediaDbId() + "/ai/" + getLlmProtocol() + "/calls/smartcreator/" + inActionName + ".json";

		String inStructure = loadInputFromTemplate(inParams, templatepath);

		JSONParser parser = new JSONParser();
		JSONObject structureDef = (JSONObject) parser.parse(inStructure);

		return chat(prepareRequest(structureDef));
	}

	public LlmResponse callRagFunction(String question, String textContent)
	{
		JSONObject obj = new JSONObject();
		obj.put("model", getModelName());

		JSONArray messages = new JSONArray();

		JSONObject message = new JSONObject();
		message.put("role", "system");
		message.put("content", "You are a helpful assistant that answers questions based only on the provided context.");
		messages.add(message);

		JSONObject usermessage = new JSONObject();
		usermessage.put("role", "user");
		usermessage.put("content", "Context: " + textContent + "\n\nQuestion: " + question);
		messages.add(usermessage);

		obj.put("messages", messages);

		LlmResponse res = callJson("/chat/completions", obj);

		JSONArray choices = (JSONArray) res.getRawResponse().get("choices");
		JSONObject choice = (JSONObject) choices.get(0);
		JSONObject resmessage = (JSONObject) choice.get("message");

		String ocrResponse = (String) resmessage.get("content");
		res.setRawMessage(ocrResponse);

		return res;

	}

	public LlmResponse createResponse()
	{
		return new OpenAiResponse();
	}
}
