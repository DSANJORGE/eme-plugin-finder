package org.entermediadb.ai.llm;

import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.llm.openai.OpenAiResponse;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.OpenEditException;
import org.openedit.data.BaseData;

import junit.framework.TestCase;

public class FailoverLlmConnectionTest extends TestCase
{
	/** Scripted connection: each call pops one outcome (reply text, Throwable, "timeout" or "empty"). */
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

		private LlmResponse nextOutcome()
		{
			calls++;
			observedTimeoutSeconds = getTimeoutSeconds();
			Object next = outcomes.isEmpty() ? "ok" : outcomes.poll();
			if ("timeout".equals(next))
			{
				throw new OpenEditException(new SocketTimeoutException("Read timed out"));
			}
			if ("empty".equals(next))
			{
				return new OpenAiResponse(); // no raw response at all
			}
			if (next instanceof Throwable)
			{
				throw new OpenEditException((Throwable) next);
			}
			return reply((String) next);
		}

		@Override
		public LlmResponse callStructure(AgentContext inContext, String inFunction)
		{
			return nextOutcome();
		}

		/** Same scripted outcomes as callStructure but a plain pass-through: needed to reach failover's own
		 * "empty response, no exception" handling instead of callStructure's own empty-payload guard. */
		@Override
		public LlmResponse callJson(String inPath, Map inPayload)
		{
			return nextOutcome();
		}

		/** A chat.completions reply whose content is the JSON {"message": text}, as the tutor templates return. */
		static LlmResponse reply(String inText)
		{
			JSONObject payload = new JSONObject();
			payload.put("message", inText);
			JSONObject message = new JSONObject();
			message.put("content", payload.toJSONString());
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
	Data routeRow;
	List<String> logged = new ArrayList<String>();

	protected Data server(String inId, String inFailures, String inMinutes)
	{
		BaseData d = new BaseData();
		d.setId(inId);
		d.setValue("connectionbean", "openaiConnection");
		d.setValue("breakerfailures", inFailures);
		d.setValue("breakerminutes", inMinutes);
		return d;
	}

	@Override
	protected void setUp()
	{
		serverA = server("a", "2", "5");
		serverB = server("b", "3", "5");
		a.setAiServerData(serverA);
		b.setAiServerData(serverB);
		routeRow = null;
	}

	protected String text(LlmResponse inResponse)
	{
		return (String) inResponse.getResponsePayload().get("message");
	}

	/** Ladder a then b; airoute lookups answer routeRow; log rows are collected as "server:status". */
	protected FailoverLlmConnection router(Data... inServers)
	{
		return routerOf("thinking", inServers);
	}

	/** Same as router(...) but with a chosen fieldServerType, so embedding/vectorize log behavior can be tested. */
	protected FailoverLlmConnection routerOf(String inType, Data... inServers)
	{
		FailoverLlmConnection r = new FailoverLlmConnection(inType)
		{
			@Override
			protected Data route(String inFunction)
			{
				return routeRow;
			}

			@Override
			protected void logAttempt(Data inServer, String inFunction, String inStatus, long inMs, int inAttempt, String inError, LlmResponse inResponse)
			{
				logged.add(inServer.getId() + ":" + inStatus);
			}
		};
		for (Data server : inServers)
		{
			r.add(server, "a".equals(server.getId()) ? a : b);
		}
		return r;
	}

	public void testPrimaryAnswers()
	{
		a.then("hola");
		LlmResponse r = router(serverA, serverB).callStructure(null, "fn");
		assertEquals("hola", text(r));
		assertEquals(0, b.calls);
		assertEquals(Arrays.asList("a:ok"), logged);
	}

	public void testFailsOverOnErrorAndTimeoutWithStatuses()
	{
		a.then(new RuntimeException("LLM HTTP 500 from x: boom"));
		b.then("desde b");
		assertEquals("desde b", text(router(serverA, serverB).callStructure(null, "fn")));
		assertEquals(Arrays.asList("a:error", "b:ok"), logged);

		logged.clear();
		BaseData route = new BaseData();
		route.setId("fn");
		route.setValue("aiservers", Arrays.asList("a", "b"));
		route.setValue("timeoutseconds", "7");
		routeRow = route;

		a.then("timeout");
		b.then("otra vez b");
		assertEquals("otra vez b", text(router(serverA, serverB).callStructure(null, "fn")));
		assertEquals(Arrays.asList("a:timeout", "b:ok"), logged);
		// The override is cleared in a finally, so it comes off both the failed attempt and the one that succeeds.
		assertEquals(Integer.valueOf(7), a.observedTimeoutSeconds);
		assertEquals(Integer.valueOf(7), b.observedTimeoutSeconds);
		assertEquals(30, a.getTimeoutSeconds());
		assertEquals(30, b.getTimeoutSeconds());
	}

	public void testEmptyReplyFailsOver()
	{
		a.then("empty");
		b.then("lleno");
		assertEquals("lleno", text(router(serverA, serverB).callStructure(null, "fn")));
		assertEquals(Arrays.asList("a:error", "b:ok"), logged);
	}

	public void testBreakerOpensAfterThresholdAndLogsSkip()
	{
		a.then("timeout").then("timeout");
		FailoverLlmConnection r = router(serverA, serverB);
		r.callStructure(null, "fn");
		r.callStructure(null, "fn");
		assertEquals(2, a.calls);

		logged.clear();
		r.callStructure(null, "fn");
		assertEquals(2, a.calls);
		assertEquals(Arrays.asList("a:breakeropen", "b:ok"), logged);
	}

	public void testSingleRecordIsTriedEvenWhenOpen()
	{
		a.then("timeout").then("timeout").then("volví");
		FailoverLlmConnection r = router(serverA);
		try
		{
			r.callStructure(null, "fn");
			fail();
		}
		catch (OpenEditException expected)
		{
		}
		try
		{
			r.callStructure(null, "fn");
			fail();
		}
		catch (OpenEditException expected)
		{
		}
		logged.clear();
		assertEquals("volví", text(r.callStructure(null, "fn")));
		assertEquals(Arrays.asList("a:ok"), logged);
	}

	public void testChainExhaustedRethrowsLastError()
	{
		a.then(new RuntimeException("LLM HTTP 429 from x: slow down"));
		b.then("timeout");
		try
		{
			router(serverA, serverB).callStructure(null, "chat_tutor_usercomment");
			fail("expected exception");
		}
		catch (OpenEditException ex)
		{
			assertTrue(ex.getMessage(), ex.getMessage().contains("Read timed out"));
		}
		assertEquals(Arrays.asList("a:error", "b:timeout"), logged);
	}

	public void testRouteReordersAppliesTimeoutAndClearsOverride()
	{
		BaseData route = new BaseData();
		route.setId("fn");
		route.setValue("aiservers", Arrays.asList("b", "a"));
		route.setValue("timeoutseconds", "7");
		routeRow = route;

		b.then("desde b");
		FailoverLlmConnection r = router(serverA, serverB);

		assertEquals("desde b", text(r.callStructure(null, "fn")));
		assertEquals(0, a.calls);
		assertEquals(1, b.calls);
		assertEquals(Integer.valueOf(7), b.observedTimeoutSeconds);
		assertEquals(30, b.getTimeoutSeconds()); // override cleared after the attempt
		assertEquals(Arrays.asList("b:ok"), logged);
	}

	public void testRouteSkipsDisabledAndUnknownIds()
	{
		serverB.setValue("disabled", "true");
		BaseData route = new BaseData();
		route.setId("fn");
		route.setValue("aiservers", Arrays.asList("nosuch", "b", "a"));
		routeRow = route;

		a.then("desde a");
		assertEquals("desde a", text(router(serverA, serverB).callStructure(null, "fn")));
		assertEquals(0, b.calls);
		assertEquals(Arrays.asList("a:ok"), logged);

		// A duplicated id is tried once, not twice: it must not cost a single call two breaker failures.
		logged.clear();
		serverB.setValue("disabled", null);
		BaseData dupeRoute = new BaseData();
		dupeRoute.setId("fn");
		dupeRoute.setValue("aiservers", Arrays.asList("a", "a", "b"));
		routeRow = dupeRoute;

		a.then(new RuntimeException("LLM HTTP 500 from x: boom"));
		b.then("desde b");
		assertEquals("desde b", text(router(serverA, serverB).callStructure(null, "fn")));
		assertEquals(Arrays.asList("a:error", "b:ok"), logged);
	}

	public void testRouteWithNoUsableIdsFallsBackToLadder()
	{
		BaseData route = new BaseData();
		route.setId("fn");
		route.setValue("aiservers", Arrays.asList("nosuch"));
		routeRow = route;

		a.then("ladder");
		assertEquals("ladder", text(router(serverA, serverB).callStructure(null, "fn")));
	}

	public void testZeroConfigValuesUseDefaults()
	{
		Data zero = server("a", "0", "0");
		zero.setValue("timeoutseconds", "0");
		a.setAiServerData(zero);
		BaseData route = new BaseData();
		route.setId("fn");
		route.setValue("aiservers", Arrays.asList("a"));
		route.setValue("timeoutseconds", "0");
		routeRow = route;

		a.then("ok");
		FailoverLlmConnection r = router(zero, serverB);
		assertEquals("ok", text(r.callStructure(null, "fn")));
		assertEquals(Integer.valueOf(30), a.observedTimeoutSeconds);
		assertEquals(3, FailoverLlmConnection.intValue(zero, "breakerfailures", 3));
	}

	public void testHttpStatusParsedFromMessage()
	{
		assertEquals(Integer.valueOf(429), FailoverLlmConnection.httpStatusOf("LLM HTTP 429 from https://x: rate limited"));
		assertNull(FailoverLlmConnection.httpStatusOf("Read timed out"));
		assertNull(FailoverLlmConnection.httpStatusOf(null));
	}

	public void testFillLogRowSetsAllFieldsAndCutsError()
	{
		serverA.setValue("modelname", "gpt-x");
		FailoverLlmConnection r = router(serverA, serverB);

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
		assertEquals(Long.valueOf(10L), row.getValue("prompttokens"));
		assertEquals(Long.valueOf(20L), row.getValue("completiontokens"));
	}

	public void testEmptyFinalAttemptReportsEmptyNotEarlierError()
	{
		a.then(new RuntimeException("LLM HTTP 500 from x: boom"));
		b.then("empty");
		try
		{
			router(serverA, serverB).callJson("fn", (Map) null);
			fail("expected exception");
		}
		catch (OpenEditException ex)
		{
			assertTrue(ex.getMessage(), ex.getMessage().contains("empty response"));
			assertFalse(ex.getMessage(), ex.getMessage().contains("boom"));
		}
		assertEquals(Arrays.asList("a:error", "b:error"), logged);
	}

	public void testEmbeddingTypeLogsFailuresOnly()
	{
		a.then(new RuntimeException("LLM HTTP 500 from x: boom"));
		b.then("ok");
		assertEquals("ok", text(routerOf("embedding", serverA, serverB).callStructure(null, "fn")));
		assertEquals(Arrays.asList("a:error"), logged);
	}

	public void testRouteBuildsEntryOnDemandForRowOutsideLadder()
	{
		final Fake c = new Fake();
		c.then("desde c");
		final Data serverC = server("c", "3", "5");
		final int[] loadCalls = { 0 };

		MediaArchive archive = new MediaArchive()
		{
			@Override
			public String getCatalogId()
			{
				return "test";
			}

			@Override
			public Data getData(String inSearchType, String inId)
			{
				return "aiserver".equals(inSearchType) && "c".equals(inId) ? serverC : null;
			}
		};

		FailoverLlmConnection r = new FailoverLlmConnection("thinking")
		{
			@Override
			protected Data route(String inFunction)
			{
				return routeRow;
			}

			@Override
			protected void logAttempt(Data inServer, String inFunction, String inStatus, long inMs, int inAttempt, String inError, LlmResponse inResponse)
			{
				logged.add(inServer.getId() + ":" + inStatus);
			}

			@Override
			protected LlmConnection loadConnection(Data inServer)
			{
				loadCalls[0]++;
				return c;
			}
		};
		r.add(serverA, a);
		r.setMediaArchive(archive);

		BaseData route = new BaseData();
		route.setId("fn");
		route.setValue("aiservers", Arrays.asList("c", "a"));
		routeRow = route;

		assertEquals("desde c", text(r.callStructure(null, "fn")));
		assertEquals(0, a.calls);
		assertEquals(Arrays.asList("c:ok"), logged);

		logged.clear();
		r.callStructure(null, "fn");
		assertEquals(1, loadCalls[0]);
	}
}
