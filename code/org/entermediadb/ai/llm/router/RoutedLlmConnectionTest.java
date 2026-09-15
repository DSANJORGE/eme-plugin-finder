package org.entermediadb.ai.llm.router;

import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.llm.BaseLlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.ai.llm.openai.OpenAiResponse;
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

		Fake then(Object inOutcome)
		{
			outcomes.add(inOutcome);
			return this;
		}

		@Override
		public LlmResponse callStructure(AgentContext inContext, String inFunction)
		{
			calls++;
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
}
