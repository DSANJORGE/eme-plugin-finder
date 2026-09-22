package org.entermediadb.ai.llm.openai;

import org.entermediadb.ai.llm.llama.LlamaOpenAiConnection;
import org.json.simple.JSONObject;
import org.openedit.data.BaseData;

import junit.framework.TestCase;

public class OpenAiConnectionPrepareTest extends TestCase
{
	protected JSONObject payload()
	{
		JSONObject p = new JSONObject();
		p.put("model", "m");
		JSONObject kw = new JSONObject();
		kw.put("enable_thinking", Boolean.FALSE);
		p.put("chat_template_kwargs", kw);
		p.put("cache_prompt", Boolean.TRUE);
		p.put("temperature", 0.3);
		return p;
	}

	public void testStripsLlamaOnlyKeysAndMergesExtraParams()
	{
		OpenAiConnection c = new OpenAiConnection();
		BaseData server = new BaseData();
		server.setValue("extraparams", "{\"top_p\": 0.9}");
		c.setAiServerData(server);

		JSONObject out = c.prepareRequest(payload());

		assertFalse(out.containsKey("chat_template_kwargs"));
		assertFalse(out.containsKey("cache_prompt"));
		assertEquals(0.9, out.get("top_p"));
		assertEquals(0.3, out.get("temperature"));
	}

	public void testKeepsLlamaKeysForLlamaProtocol()
	{
		LlamaOpenAiConnection c = new LlamaOpenAiConnection();
		c.setAiServerData(new BaseData());

		JSONObject out = c.prepareRequest(payload());

		assertTrue(out.containsKey("chat_template_kwargs"));
		assertTrue(out.containsKey("cache_prompt"));
	}

	public void testBlankExtraParamsIsNoop()
	{
		OpenAiConnection c = new OpenAiConnection();
		BaseData server = new BaseData();
		server.setValue("extraparams", "  ");
		c.setAiServerData(server);
		assertEquals("m", c.prepareRequest(payload()).get("model"));
	}

	public void testInvalidExtraParamsIsIgnored()
	{
		OpenAiConnection c = new OpenAiConnection();
		BaseData server = new BaseData();
		server.setValue("extraparams", "{not json");
		c.setAiServerData(server);
		assertEquals("m", c.prepareRequest(payload()).get("model"));
	}

	public void testTimeoutDefaultRowAndOverride()
	{
		OpenAiConnection c = new OpenAiConnection();
		BaseData server = new BaseData();
		c.setAiServerData(server);
		assertEquals(30, c.getTimeoutSeconds());

		server.setValue("timeoutseconds", "45");
		assertEquals(45, c.getTimeoutSeconds());

		server.setValue("timeoutseconds", "9999");
		assertEquals(1200, c.getTimeoutSeconds());

		server.setValue("timeoutseconds", "0");
		assertEquals(30, c.getTimeoutSeconds());

		server.setValue("timeoutseconds", "-5");
		assertEquals(30, c.getTimeoutSeconds());

		c.setTimeoutOverride(7);
		assertEquals(7, c.getTimeoutSeconds());
	}

	public void testEnableThinkingFalseBecomesLowReasoningEffort()
	{
		OpenAiConnection c = new OpenAiConnection();
		c.setAiServerData(new BaseData());

		JSONObject out = c.prepareRequest(payload());

		assertEquals("low", out.get("reasoning_effort"));
		assertFalse(out.containsKey("chat_template_kwargs"));

		JSONObject explicit = payload();
		explicit.put("reasoning_effort", "high");
		assertEquals("high", c.prepareRequest(explicit).get("reasoning_effort"));
	}

	public void testSalvageRecoversOnePropertySchema()
	{
		OpenAiConnection c = new OpenAiConnection();
		JSONObject request = new org.openedit.util.JSONParser().parse("{\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{\"schema\":{\"required\":[\"message\"]}}}}");
		String body = "{\"error\":{\"code\":\"json_validate_failed\",\"failed_generation\":\" Hola, colega. \"}}";

		JSONObject out = c.salvageStructure(body, request);

		OpenAiResponse response = new OpenAiResponse();
		response.setRawResponse(out);
		assertEquals("Hola, colega.", response.getResponsePayload().get("message"));
	}

	public void testSalvageRefusesOtherErrorsAndSchemas()
	{
		OpenAiConnection c = new OpenAiConnection();
		JSONObject one = new org.openedit.util.JSONParser().parse("{\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{\"schema\":{\"required\":[\"message\"]}}}}");
		JSONObject two = new org.openedit.util.JSONParser().parse("{\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{\"schema\":{\"required\":[\"message\",\"hint\"]}}}}");

		assertNull(c.salvageStructure("{\"error\":{\"code\":\"rate_limit\"}}", one));
		assertNull(c.salvageStructure("{\"error\":{\"code\":\"json_validate_failed\",\"failed_generation\":\"x\"}}", two));
		assertNull(c.salvageStructure("<html>502</html>", one));
		assertNull(c.salvageStructure("", one));
	}
}
