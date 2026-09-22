package org.entermediadb.ai.llm.anthropic;

import java.nio.charset.StandardCharsets;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.ai.llm.openai.OpenAiConnection;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.OpenEditException;

/**
 * Anthropic Messages API behind the OpenAI-shaped call templates. Templates render exactly as for
 * OpenAI (getLlmProtocol() stays "openai"), the request is translated on the way out and the reply
 * is translated back into a chat.completions shape so OpenAiResponse parsing is reused.
 */
public class AnthropicConnection extends OpenAiConnection
{
	private static Log log = LogFactory.getLog(AnthropicConnection.class);

	public static final String ANTHROPIC_VERSION = "2023-06-01";
	public static final long DEFAULT_MAX_TOKENS = 1024L;

	/** Anthropic uses x-api-key + anthropic-version, not Authorization: Bearer. */
	@Override
	protected LlmResponse chat(JSONObject inPayload)
	{
		JSONObject request = toAnthropicRequest(inPayload);
		log.info("Sent: " + request.toJSONString());

		HttpPost method = new HttpPost(getServerRoot() + "/messages");
		method.addHeader("x-api-key", getApiKey());
		method.addHeader("anthropic-version", ANTHROPIC_VERSION);
		method.setHeader("Content-Type", "application/json");
		applyLlmHeaders(method, getSharedHeaders());
		method.setEntity(new StringEntity(request.toJSONString(), StandardCharsets.UTF_8));

		CloseableHttpResponse resp = execute(method);
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
				if (body.length() > 500)
				{
					body = body.substring(0, 500);
				}
				throw new OpenEditException("LLM HTTP " + status + " from " + getServerRoot() + ": " + body);
			}
			JSONObject json = (JSONObject) getConnection().parseMap(resp);
			log.info("Returned: " + json.toJSONString());
			if (isRefusal(json))
			{
				throw new OpenEditException("Anthropic refusal for model " + getModelName());
			}
			LlmResponse response = createResponse();
			response.setRawResponse(toOpenAiResponse(json));
			return response;
		}
		finally
		{
			getConnection().release(resp);
		}
	}

	public static boolean isRefusal(JSONObject inAnthropic)
	{
		return "refusal".equals(inAnthropic.get("stop_reason"));
	}

	public static JSONObject toAnthropicRequest(JSONObject inOpenAi)
	{
		JSONObject out = new JSONObject();
		out.put("model", inOpenAi.get("model"));
		Object maxTokens = inOpenAi.get("max_tokens");
		if (maxTokens == null)
		{
			maxTokens = inOpenAi.get("max_completion_tokens");
		}
		out.put("max_tokens", maxTokens == null ? DEFAULT_MAX_TOKENS : maxTokens);
		// temperature/top_p/top_k are deliberately not forwarded: current Claude models answer
		// HTTP 400 when they are sent. An admin who needs them can add them back in extraparams.
		for (String key : new String[] { "stop_sequences", "thinking", "metadata" })
		{
			if (inOpenAi.get(key) != null)
			{
				out.put(key, inOpenAi.get(key));
			}
		}
		Object stop = inOpenAi.get("stop");
		if (stop != null && out.get("stop_sequences") == null)
		{
			if (stop instanceof JSONArray)
			{
				out.put("stop_sequences", stop);
			}
			else
			{
				JSONArray sequences = new JSONArray();
				sequences.add(stop);
				out.put("stop_sequences", sequences);
			}
		}

		StringBuilder system = new StringBuilder();
		JSONArray messages = new JSONArray();
		JSONArray inMessages = (JSONArray) inOpenAi.get("messages");
		if (inMessages != null)
		{
			for (Object o : inMessages)
			{
				JSONObject m = (JSONObject) o;
				String role = (String) m.get("role");
				if ("system".equals(role))
				{
					if (system.length() > 0)
					{
						system.append("\n\n");
					}
					system.append(String.valueOf(m.get("content")));
					continue;
				}
				JSONObject converted = new JSONObject();
				converted.put("role", "assistant".equals(role) ? "assistant" : "user");
				converted.put("content", toAnthropicContent(m.get("content")));
				messages.add(converted);
			}
		}
		if (system.length() > 0)
		{
			out.put("system", system.toString());
		}
		if (messages.isEmpty())
		{
			// Anthropic rejects an empty messages array; a system-only template still deserves an answer.
			JSONObject placeholder = new JSONObject();
			placeholder.put("role", "user");
			placeholder.put("content", "(no input)");
			messages.add(placeholder);
		}
		out.put("messages", messages);

		JSONObject responseFormat = (JSONObject) inOpenAi.get("response_format");
		if (responseFormat != null && "json_schema".equals(responseFormat.get("type")))
		{
			JSONObject jsonSchema = (JSONObject) responseFormat.get("json_schema");
			JSONObject format = new JSONObject();
			format.put("type", "json_schema");
			format.put("schema", jsonSchema.get("schema"));
			JSONObject outputConfig = new JSONObject();
			outputConfig.put("format", format);
			out.put("output_config", outputConfig);
		}

		JSONArray tools = (JSONArray) inOpenAi.get("tools");
		if (tools != null && !tools.isEmpty())
		{
			JSONArray converted = new JSONArray();
			for (Object o : tools)
			{
				JSONObject fn = (JSONObject) ((JSONObject) o).get("function");
				JSONObject tool = new JSONObject();
				tool.put("name", fn.get("name"));
				if (fn.get("description") != null)
				{
					tool.put("description", fn.get("description"));
				}
				Object parameters = fn.get("parameters");
				if (parameters == null)
				{
					JSONObject empty = new JSONObject();
					empty.put("type", "object");
					parameters = empty;
				}
				tool.put("input_schema", parameters);
				converted.add(tool);
			}
			out.put("tools", converted);

			Object toolChoice = inOpenAi.get("tool_choice");
			if (toolChoice != null)
			{
				if (toolChoice instanceof JSONObject)
				{
					JSONObject choice = (JSONObject) toolChoice;
					if (choice.get("function") != null)
					{
						JSONObject mapped = new JSONObject();
						mapped.put("type", "tool");
						mapped.put("name", ((JSONObject) choice.get("function")).get("name"));
						out.put("tool_choice", mapped);
					}
				}
				else if (toolChoice instanceof String)
				{
					String choice = (String) toolChoice;
					if ("required".equals(choice))
					{
						JSONObject mapped = new JSONObject();
						mapped.put("type", "any");
						out.put("tool_choice", mapped);
					}
					else if ("auto".equals(choice))
					{
						JSONObject mapped = new JSONObject();
						mapped.put("type", "auto");
						out.put("tool_choice", mapped);
					}
					// "none" drops the key (do nothing)
					// anything else is ignored (do nothing)
				}
			}
		}
		return out;
	}

	/** String content passes through; OpenAI content parts become Anthropic text/image blocks. */
	protected static Object toAnthropicContent(Object inContent)
	{
		if (!(inContent instanceof JSONArray))
		{
			return inContent == null ? "" : inContent;
		}
		JSONArray blocks = new JSONArray();
		for (Object o : (JSONArray) inContent)
		{
			JSONObject part = (JSONObject) o;
			String type = (String) part.get("type");
			if ("image_url".equals(type))
			{
				String url = (String) ((JSONObject) part.get("image_url")).get("url");
				JSONObject source = new JSONObject();
				int comma = url == null ? -1 : url.indexOf(',');
				if (url != null && url.startsWith("data:") && comma > 0)
				{
					String header = url.substring(5, comma); // image/png;base64
					source.put("type", "base64");
					source.put("media_type", header.split(";")[0]);
					source.put("data", url.substring(comma + 1));
				}
				else
				{
					source.put("type", "url");
					source.put("url", url);
				}
				JSONObject image = new JSONObject();
				image.put("type", "image");
				image.put("source", source);
				blocks.add(image);
			}
			else
			{
				JSONObject text = new JSONObject();
				text.put("type", "text");
				text.put("text", String.valueOf(part.get("text")));
				blocks.add(text);
			}
		}
		return blocks;
	}

	public static JSONObject toOpenAiResponse(JSONObject inAnthropic)
	{
		JSONObject out = new JSONObject();
		out.put("id", inAnthropic.get("id"));
		out.put("object", "chat.completion");
		out.put("model", inAnthropic.get("model"));

		StringBuilder text = new StringBuilder();
		JSONArray toolCalls = new JSONArray();
		JSONArray content = (JSONArray) inAnthropic.get("content");
		if (content != null)
		{
			for (Object o : content)
			{
				JSONObject part = (JSONObject) o;
				String type = (String) part.get("type");
				if ("text".equals(type))
				{
					text.append(String.valueOf(part.get("text")));
				}
				else if ("tool_use".equals(type))
				{
					JSONObject function = new JSONObject();
					function.put("name", part.get("name"));
					Object input = part.get("input");
					function.put("arguments", input instanceof JSONObject ? ((JSONObject) input).toJSONString() : "{}");
					JSONObject call = new JSONObject();
					call.put("id", part.get("id"));
					call.put("type", "function");
					call.put("function", function);
					toolCalls.add(call);
				}
			}
		}

		JSONObject message = new JSONObject();
		message.put("role", "assistant");
		message.put("content", text.length() > 0 ? text.toString() : null);
		if (!toolCalls.isEmpty())
		{
			message.put("tool_calls", toolCalls);
		}

		String stop = (String) inAnthropic.get("stop_reason");
		JSONObject choice = new JSONObject();
		choice.put("index", 0L);
		choice.put("message", message);
		choice.put("finish_reason", "tool_use".equals(stop) ? "tool_calls" : "max_tokens".equals(stop) ? "length" : "stop");
		JSONArray choices = new JSONArray();
		choices.add(choice);
		out.put("choices", choices);

		JSONObject usage = (JSONObject) inAnthropic.get("usage");
		if (usage != null)
		{
			long in = asLong(usage.get("input_tokens"));
			long outTokens = asLong(usage.get("output_tokens"));
			JSONObject converted = new JSONObject();
			converted.put("prompt_tokens", in);
			converted.put("completion_tokens", outTokens);
			converted.put("total_tokens", in + outTokens);
			out.put("usage", converted);
		}
		return out;
	}

	protected static long asLong(Object inValue)
	{
		return inValue instanceof Number ? ((Number) inValue).longValue() : 0L;
	}
}
