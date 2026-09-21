package org.entermediadb.ai.llm.openai;

import java.nio.charset.StandardCharsets;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.llm.BaseLlmConnection;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.CatalogEnabled;
import org.openedit.OpenEditException;
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

		CloseableHttpResponse resp = getConnection().sharedExecute(method);

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

		LlmResponse res = callJson("/chat/completions", obj);
		return res;

	}

	@Override
	public LlmResponse callClassifyFunction(AgentContext params, String inFunction, String inBase64Image)
	{
		return callClassifyFunction(params, inFunction, inBase64Image, null);
	}

	public LlmResponse callClassifyFunction(AgentContext inAgentContext, String inFunction, String inBase64Image, String textContent)
	{
		MediaArchive archive = getMediaArchive();

		inAgentContext.put("model", getModelName());

		if (textContent != null)
		{
			inAgentContext.put("textcontent", textContent);
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

		String definition = loadInputFromTemplate(inAgentContext, templatepath);

		JSONParser parser = new JSONParser();
		JSONObject payload = (JSONObject) parser.parse(definition);

		log.info(payload);

		attachImageMessage(payload, inBase64Image);

		LlmResponse res = callJson("/chat/completions", payload);
		return res;
	}

	public LlmResponse callToolsFunction(AgentContext params, String inFunction)
	{
		MediaArchive archive = getMediaArchive();

		params.put("model", getModelName());

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

		String definition = loadInputFromTemplate(params, templatepath);

		JSONParser parser = new JSONParser();
		JSONObject payload = (JSONObject) parser.parse(definition);

		log.info(payload);

		LlmResponse res = callJson("/chat/completions", payload);
		return res;
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
		inParams.put("model", getModelName());

		if (inParams.getContextValue("jsonfilename") != null)
		{
			inFunctionName = (String) inParams.getContextValue("jsonfilename");
		}

		String templatepath = "/" + getMediaArchive().getMediaDbId() + "/ai/" + getLlmProtocol() + "/calls/" + inFunctionName + ".json";

		String inStructure = loadInputFromTemplate(inParams, templatepath);

		JSONParser parser = new JSONParser();
		JSONObject structureDef = (JSONObject) parser.parse(inStructure);

		stripLlamaExtensions(structureDef);

		log.info("Sent: " + structureDef.toJSONString());

		HttpPost method = new HttpPost(getServerRoot() + "/chat/completions");
		method.addHeader("Authorization", "Bearer " + getApiKey());
		method.setHeader("Content-Type", "application/json");
		method.setEntity(new StringEntity(structureDef.toJSONString(), StandardCharsets.UTF_8));

		log.info("Calling: " + inFunctionName + " on: " + method.getURI() + "");

		CloseableHttpResponse resp = getConnection().sharedExecute(method);

		// TestU local patch: groq rate-limits bursts (two learners asking at once is
		// enough), and the learner is told IRIS is slow for an answer never attempted.
		// One retry costs two seconds; a second provider is the routing work's job.
		for (int tries = 0; tries < 2 && resp.getStatusLine().getStatusCode() == 429; tries++)
		{
			getConnection().release(resp);
			log.info("Rate limited, retrying: " + inFunctionName);
			try
			{
				Thread.sleep(2000);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				break;
			}
			resp = getConnection().sharedExecute(method);
		}

		try
		{
			if (resp.getStatusLine().getStatusCode() != 200)
			{
				JSONObject salvaged = salvageStructure(resp, structureDef);
				if (salvaged == null)
				{
					throw new OpenEditException("OpenAI error: " + resp.getStatusLine());
				}
				log.info("Salvaged: " + salvaged.toJSONString());
				LlmResponse salvage = createResponse();
				salvage.setRawResponse(salvaged);
				return salvage;
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
	 * TestU local patch: `chat_template_kwargs` (enable_thinking) and `id_slot` are llama.cpp server
	 * extensions. A shared call template carries them for llamat, where enable_thinking false saves
	 * ~8 s a reply; groq answers 400 "property 'chat_template_kwargs' is unsupported" and anthropic
	 * "Extra inputs are not permitted", so every template without an openai/ fork failed outright
	 * (analytics_classify_questions on every mastery run, Diego 2026-09-20). Dropping them here keeps
	 * one prompt per call instead of a fork per provider; LlamaOpenAiConnection reports protocol
	 * "llama" and is left untouched.
	 *
	 * enable_thinking false is translated rather than dropped: it is llama.cpp's way of saying "do not
	 * reason before answering", and `reasoning_effort` is the OpenAI-compatible way. It is not cosmetic
	 * on groq's free tier, where 8000 tokens/minute is the binding limit: a classify batch spent 1380
	 * of its 1835 completion tokens on reasoning, so five batches exhausted the minute and the rest of
	 * the run 429'd. Only a template that already asked for thinking off is affected.
	 */
	protected void stripLlamaExtensions(JSONObject inRequest)
	{
		if ("llama".equals(getLlmProtocol()))
		{
			return;
		}
		JSONObject kwargs = (JSONObject) inRequest.get("chat_template_kwargs");
		if (kwargs != null && Boolean.FALSE.equals(kwargs.get("enable_thinking")) && inRequest.get("reasoning_effort") == null)
		{
			inRequest.put("reasoning_effort", "low");
		}
		inRequest.remove("chat_template_kwargs");
		inRequest.remove("id_slot");
	}

	/**
	 * TestU local patch: the answer inside a `json_validate_failed` error, as if it had come back
	 * normally. A reasoning model (groq's gpt-oss-120b) writes a good reply often enough and then
	 * fails to wrap it in the schema; groq answers 400 with the prose in `failed_generation`, so the
	 * learner saw "IRIS is taking longer than usual" for a reply already written and paid for
	 * (Diego, 2026-09-20). Only a schema of one required property can be filled this way; anything
	 * else returns null and the caller throws as before.
	 */
	protected JSONObject salvageStructure(CloseableHttpResponse inResponse, JSONObject inRequest)
	{
		try
		{
			// parseMap throws on a non-200 instead of handing back the body, and the body
			// is the whole point here, so read the entity directly.
			String raw = org.apache.http.util.EntityUtils.toString(inResponse.getEntity(), StandardCharsets.UTF_8);
			JSONObject body = (JSONObject) new JSONParser().parse(raw);
			JSONObject error = body == null ? null : (JSONObject) body.get("error");
			if (error == null || !"json_validate_failed".equals(error.get("code")))
			{
				// Whatever it is, it is worth reading: the status line alone said nothing.
				log.info("Not salvageable: " + raw);
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
			log.error("Could not read the error body", e);
			return null;
		}
	}

	@Override
	public LlmResponse callSmartCreatorAiAction(AgentContext inParams, String inActionName)
	{
		inParams.put("model", getModelName());

		String templatepath = "/" + getMediaArchive().getMediaDbId() + "/ai/" + getLlmProtocol() + "/calls/smartcreator/" + inActionName + ".json";

		String inStructure = loadInputFromTemplate(inParams, templatepath);

		JSONParser parser = new JSONParser();
		JSONObject structureDef = (JSONObject) parser.parse(inStructure);

		stripLlamaExtensions(structureDef);

		log.info("Sent: " + structureDef.toJSONString());

		HttpPost method = new HttpPost(getServerRoot() + "/chat/completions");
		method.addHeader("authorization", "Bearer " + getApiKey());
		method.setHeader("Content-Type", "application/json");
		method.setEntity(new StringEntity(structureDef.toJSONString(), StandardCharsets.UTF_8));

		CloseableHttpResponse resp = getConnection().sharedExecute(method);

		try
		{
			if (resp.getStatusLine().getStatusCode() != 200)
			{
				throw new OpenEditException("OpenAI error: " + resp.getStatusLine());
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
