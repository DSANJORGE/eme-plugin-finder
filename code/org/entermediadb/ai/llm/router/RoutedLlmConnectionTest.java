package org.entermediadb.ai.llm.router;

import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.llm.BaseLlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.ai.llm.openai.OpenAiResponse;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.OpenEditException;
import org.openedit.data.BaseData;

import junit.framework.TestCase;

public class RoutedLlmConnectionTest extends TestCase
{
	/** Scripted delegate: each call pops one outcome (reply text, Throwable, or "timeout"). */
	static class Fake extends BaseLlmConnection
	{
		Deque<Object> outcomes = new ArrayDeque<Object>();
		int calls;
		Integer observedTimeoutSeconds;

		Fake then(Object inOutcome)
		{
			outcomes.add(inOutcome);
			return this;
		}

		@Override
		public LlmResponse callStructure(AgentContext inContext, String inFunction)
		{
			calls++;
			observedTimeoutSeconds = getTimeoutSeconds();
			Object next = outcomes.isEmpty() ? "ok" : outcomes.poll();
			if ("timeout".equals(next))
			{
				throw new OpenEditException(new SocketTimeoutException("Read timed out"));
			}
			if (next instanceof Throwable)
			{
				throw new OpenEditException((Throwable) next);
			}
			return reply((String) next);
		}

		static LlmResponse reply(String inText)
		{
			JSONObject message = new JSONObject();
			message.put("content", inText);
			JSONObject choice = new JSONObject();
			choice.put("message", message);
			JSONArray choices = new JSONArray();
			choices.add(choice);
			JSONObject raw = new JSONObject();
			raw.put("choices", choices);
			OpenAiResponse r = new OpenAiResponse();
			r.setRawResponse(raw);
			return r;
		}
	}

	Fake a = new Fake();
	Fake b = new Fake();
	Data serverA;
	Data serverB;
	List<String> logged = new ArrayList<String>();

	protected Data server(String inId, String inFailures, String inMinutes)
	{
		BaseData d = new BaseData();
		d.setId(inId);
		d.setValue("connectionbean", "openaiConnection");
		d.setValue("serverapikey", "k");
		d.setValue("breakerfailures", inFailures);
		d.setValue("breakerminutes", inMinutes);
		return d;
	}

	@Override
	protected void setUp()
	{
		RoutedLlmConnection.BREAKERS.clear();
		serverA = server("a", "2", "5");
		serverB = server("b", "3", "5");
	}

	protected RoutedLlmConnection router(Data... inServers)
	{
		return new RoutedLlmConnection(null, "thinking", Arrays.asList(inServers), new RoutedLlmConnection.DelegateFactory()
		{
			public org.entermediadb.ai.llm.LlmConnection create(Data inServer)
			{
				return "a".equals(inServer.getId()) ? a : b;
			}
		})
		{
			@Override
			protected void logAttempt(Data inServer, String inFunction, String inStatus, long inMs, int inAttempt, String inError, LlmResponse inResponse)
			{
				logged.add(inServer.getId() + ":" + inStatus);
			}
		};
	}

	public void testPrimaryAnswers()
	{
		a.then("hola");
		LlmResponse r = router(serverA, serverB).callStructure(null, "fn");
		assertEquals("hola", r.getMessage());
		assertEquals(0, b.calls);
		assertEquals(Arrays.asList("a:ok"), logged);
	}

	public void testFailsOverOnErrorAndTimeout()
	{
		a.then(new RuntimeException("HTTP 500"));
		b.then("desde b");
		assertEquals("desde b", router(serverA, serverB).callStructure(null, "fn").getMessage());
		assertEquals(Arrays.asList("a:error", "b:ok"), logged);

		logged.clear();
		a.then("timeout");
		b.then("otra vez b");
		assertEquals("otra vez b", router(serverA, serverB).callStructure(null, "fn").getMessage());
		assertEquals(Arrays.asList("a:timeout", "b:ok"), logged);
	}

	public void testEmptyMessageFailsOver()
	{
		a.then("   ");
		b.then("lleno");
		assertEquals("lleno", router(serverA, serverB).callStructure(null, "fn").getMessage());
		assertEquals(Arrays.asList("a:error", "b:ok"), logged);
	}

	public void testBreakerOpensAfterThresholdAndSkips()
	{
		a.then("timeout").then("timeout");
		RoutedLlmConnection r = router(serverA, serverB);
		r.callStructure(null, "fn");
		r.callStructure(null, "fn");
		assertEquals(2, a.calls);

		logged.clear();
		r.callStructure(null, "fn");
		assertEquals(2, a.calls);
		assertEquals(Arrays.asList("a:breakeropen", "b:ok"), logged);
	}

	public void testHalfOpenProbeClosesBreaker()
	{
		serverA = server("a", "1", "0"); // opens after 1 failure, window 0 minutes
		a.then(new RuntimeException("boom")).then("volví");
		RoutedLlmConnection r = router(serverA, serverB);
		r.callStructure(null, "fn");
		logged.clear();
		assertEquals("volví", r.callStructure(null, "fn").getMessage());
		assertEquals(Arrays.asList("a:breakerclosed", "a:ok"), logged);
		assertEquals(0, RoutedLlmConnection.BREAKERS.get("test/a").failures);
	}

	public void testMisconfiguredRowSkippedWithoutBreakerCount()
	{
		serverA.setValue("serverapikey", "");
		b.then("b");
		router(serverA, serverB).callStructure(null, "fn");
		assertEquals(0, a.calls);
		assertEquals(Arrays.asList("a:misconfigured", "b:ok"), logged);
		assertEquals(0, RoutedLlmConnection.BREAKERS.get("test/a").failures);
	}

	public void testChainExhaustedNamesEveryServer()
	{
		a.then(new RuntimeException("HTTP 429"));
		b.then("timeout");
		try
		{
			router(serverA, serverB).callStructure(null, "chat_tutor_usercomment");
			fail("expected exception");
		}
		catch (OpenEditException ex)
		{
			assertTrue(ex.getMessage(), ex.getMessage().startsWith("No AI server answered chat_tutor_usercomment"));
			assertTrue(ex.getMessage(), ex.getMessage().contains("a(error"));
			assertTrue(ex.getMessage(), ex.getMessage().contains("b(timeout"));
		}
	}

	public void testNoServersThrowsMisconfigured()
	{
		try
		{
			router().callStructure(null, "fn");
			fail("expected exception");
		}
		catch (OpenEditException ex)
		{
			assertEquals("No enabled aiserver of type thinking for fn", ex.getMessage());
		}
	}

	public void testHttpStatusParsedFromMessage()
	{
		assertEquals(Integer.valueOf(429), RoutedLlmConnection.httpStatusOf("LLM HTTP 429 from https://x: rate limited"));
		assertNull(RoutedLlmConnection.httpStatusOf("Read timed out"));
	}

	public void testFillLogRowSetsAllFieldsAndCutsError()
	{
		serverA.setValue("modelname", "gpt-x");
		RoutedLlmConnection r = router(serverA, serverB);

		JSONObject usage = new JSONObject();
		usage.put("prompt_tokens", 10L);
		usage.put("completion_tokens", 20L);
		JSONObject raw = new JSONObject();
		raw.put("usage", usage);
		OpenAiResponse response = new OpenAiResponse();
		response.setRawResponse(raw);

		StringBuilder longError = new StringBuilder("LLM HTTP 503 from x: ");
		for (int i = 0; i < 600; i++)
		{
			longError.append('e');
		}

		BaseData row = new BaseData();
		r.fillLogRow(row, serverA, "fn", "error", 123L, 2, longError.toString(), response);

		assertEquals("fn", row.get("functionname"));
		assertEquals("a", row.get("aiserver"));
		assertEquals("gpt-x", row.get("modelname"));
		assertEquals("error", row.get("status"));
		assertEquals("123", row.get("ms"));
		assertEquals("2", row.get("attempt"));
		assertNotNull(row.getValue("datecreated"));
		assertEquals(500, row.get("errormessage").length());
		assertEquals(Integer.valueOf(503), row.getValue("httpstatus"));
		assertEquals(Long.valueOf(10L), row.getValue("promptokens"));
		assertEquals(Long.valueOf(20L), row.getValue("completiontokens"));
	}

	protected MediaArchive archiveWithRoute(final Data inRoute, final Map<String, Data> inServersById)
	{
		return new MediaArchive()
		{
			@Override
			public String getCatalogId()
			{
				return "test";
			}

			@Override
			public Data getData(String inSearchType, String inId)
			{
				if ("airoute".equals(inSearchType))
				{
					return inRoute;
				}
				if ("aiserver".equals(inSearchType))
				{
					return inServersById.get(inId);
				}
				return null;
			}
		};
	}

	protected RoutedLlmConnection routedOn(MediaArchive inArchive)
	{
		return new RoutedLlmConnection(inArchive, "thinking", Arrays.asList(serverA, serverB), new RoutedLlmConnection.DelegateFactory()
		{
			public org.entermediadb.ai.llm.LlmConnection create(Data inServer)
			{
				return "a".equals(inServer.getId()) ? a : b;
			}
		})
		{
			@Override
			protected void logAttempt(Data inServer, String inFunction, String inStatus, long inMs, int inAttempt, String inError, LlmResponse inResponse)
			{
				logged.add(inServer.getId() + ":" + inStatus);
			}
		};
	}

	public void testAirouteOrdersServersAppliesTimeoutAndClearsOverride()
	{
		BaseData route = new BaseData();
		route.setId("fn");
		route.setValue("aiservers", Arrays.asList("b", "a"));
		route.setValue("timeoutseconds", "7");

		Map<String, Data> byId = new HashMap<String, Data>();
		byId.put("a", serverA);
		byId.put("b", serverB);

		b.then("desde b");
		RoutedLlmConnection r = routedOn(archiveWithRoute(route, byId));

		assertEquals("desde b", r.callStructure(null, "fn").getMessage());
		assertEquals(0, a.calls);
		assertEquals(1, b.calls);
		assertEquals(Integer.valueOf(7), b.observedTimeoutSeconds);
		assertEquals(30, b.getTimeoutSeconds()); // override cleared after the attempt
	}

	public void testAirouteSkipsDisabledServer()
	{
		Data disabledC = server("c", "2", "5");
		disabledC.setValue("enabled", "false");

		BaseData route = new BaseData();
		route.setId("fn");
		route.setValue("aiservers", Arrays.asList("c", "b", "a"));

		Map<String, Data> byId = new HashMap<String, Data>();
		byId.put("a", serverA);
		byId.put("b", serverB);
		byId.put("c", disabledC);

		b.then("desde b");
		RoutedLlmConnection r = routedOn(archiveWithRoute(route, byId));

		assertEquals("desde b", r.callStructure(null, "fn").getMessage());
		assertEquals(Arrays.asList("b:ok"), logged);
	}
}
