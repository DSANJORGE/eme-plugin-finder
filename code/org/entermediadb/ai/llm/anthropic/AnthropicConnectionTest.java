package org.entermediadb.ai.llm.anthropic;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.util.JSONParser;

import junit.framework.TestCase;

public class AnthropicConnectionTest extends TestCase
{
	protected JSONObject parse(String inJson)
	{
		return new JSONParser().parse(inJson);
	}

	public void testTranslatesTutorShapedRequest()
	{
		JSONObject in = parse("{\"model\":\"claude-opus-5\",\"messages\":[{\"role\":\"system\",\"content\":\"You are Iris.\"},{\"role\":\"user\",\"content\":\"Hola\"}],\"temperature\":0.3,\"max_tokens\":400,\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"tutor_reply\",\"strict\":true,\"schema\":{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}},\"required\":[\"message\"],\"additionalProperties\":false}}}}");

		JSONObject out = AnthropicConnection.toAnthropicRequest(in);

		assertEquals("claude-opus-5", out.get("model"));
		assertEquals("You are Iris.", out.get("system"));
		assertEquals(400L, out.get("max_tokens"));
		assertEquals(0.3, out.get("temperature"));
		JSONArray messages = (JSONArray) out.get("messages");
		assertEquals(1, messages.size());
		assertEquals("user", ((JSONObject) messages.get(0)).get("role"));
		JSONObject format = (JSONObject) ((JSONObject) out.get("output_config")).get("format");
		assertEquals("json_schema", format.get("type"));
		assertNotNull(((JSONObject) format.get("schema")).get("properties"));
		assertFalse(out.containsKey("response_format"));
		assertFalse(out.containsKey("chat_template_kwargs"));
	}

	public void testMaxTokensDefaultsAndToolsTranslate()
	{
		JSONObject in = parse("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"pick\",\"description\":\"d\",\"parameters\":{\"type\":\"object\"}}}],\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"pick\"}}}");

		JSONObject out = AnthropicConnection.toAnthropicRequest(in);

		assertEquals(1024L, out.get("max_tokens"));
		JSONObject tool = (JSONObject) ((JSONArray) out.get("tools")).get(0);
		assertEquals("pick", tool.get("name"));
		assertNotNull(tool.get("input_schema"));
		assertEquals("tool", ((JSONObject) out.get("tool_choice")).get("type"));
		assertEquals("pick", ((JSONObject) out.get("tool_choice")).get("name"));
	}

	public void testImagePartsTranslate()
	{
		JSONObject in = parse("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"what\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}]}]}");

		JSONArray content = (JSONArray) ((JSONObject) ((JSONArray) AnthropicConnection.toAnthropicRequest(in).get("messages")).get(0)).get("content");

		assertEquals("text", ((JSONObject) content.get(0)).get("type"));
		JSONObject source = (JSONObject) ((JSONObject) content.get(1)).get("source");
		assertEquals("base64", source.get("type"));
		assertEquals("image/png", source.get("media_type"));
		assertEquals("AAAA", source.get("data"));
	}

	public void testTextResponseBecomesOpenAiChoice()
	{
		JSONObject a = parse("{\"id\":\"msg_1\",\"model\":\"claude-opus-5\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"message\\\":\\\"hola\\\"}\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":5}}");

		JSONObject out = AnthropicConnection.toOpenAiResponse(a);

		JSONObject message = (JSONObject) ((JSONObject) ((JSONArray) out.get("choices")).get(0)).get("message");
		assertEquals("{\"message\":\"hola\"}", message.get("content"));
		JSONObject usage = (JSONObject) out.get("usage");
		assertEquals(12L, usage.get("prompt_tokens"));
		assertEquals(5L, usage.get("completion_tokens"));
		assertEquals(17L, usage.get("total_tokens"));
	}

	public void testToolUseResponseBecomesToolCall()
	{
		JSONObject a = parse("{\"id\":\"msg_2\",\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"pick\",\"input\":{\"k\":\"v\"}}]}");

		JSONObject out = AnthropicConnection.toOpenAiResponse(a);

		JSONObject message = (JSONObject) ((JSONObject) ((JSONArray) out.get("choices")).get(0)).get("message");
		JSONObject call = (JSONObject) ((JSONArray) message.get("tool_calls")).get(0);
		assertEquals("pick", ((JSONObject) call.get("function")).get("name"));
		assertEquals("{\"k\":\"v\"}", ((JSONObject) call.get("function")).get("arguments"));
	}

	public void testRefusalIsDetected()
	{
		JSONObject a = parse("{\"id\":\"msg_3\",\"stop_reason\":\"refusal\",\"content\":[]}");
		assertTrue(AnthropicConnection.isRefusal(a));
		assertFalse(AnthropicConnection.isRefusal(parse("{\"stop_reason\":\"end_turn\"}")));
	}
}
