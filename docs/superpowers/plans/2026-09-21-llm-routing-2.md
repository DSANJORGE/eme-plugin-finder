# LLM routing 2 (salvage onto FailoverLlmConnection) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the six `llm-routing` branch pieces (Anthropic adapter, eval endpoint + harness, `aicalllog`, `airoute`, per-row timeout/extraparams, tests + connection fixes) onto `main`'s `FailoverLlmConnection`, on branch `llm-routing-2` in finder, catalog and mediadb.

**Architecture:** `FailoverLlmConnection` stays the one router (ladder + breaker from `main`) and gains an `airoute` lookup, a route timeout override and one `aicalllog` row per attempt. `BaseLlmConnection`/`OpenAiConnection` take the branch's structure (`execute` with per-row timeout, `prepareRequest`, `loadCallPayload`, single `chat()`), with `main`'s 429 retry, `salvageStructure` and `enable_thinking` translation folded in. `AnthropicConnection`, `LlmEvalModule`, `AiCallLogCleaner`, the harness, and the catalog/mediadb files move over from the branch, mostly verbatim via `git checkout llm-routing -- <path>`.

**Tech Stack:** Java 21 (EnterMedia plugins), Elasticsearch-backed `Data`, Apache HttpClient 4.5 via `HttpSharedConnection`, json-simple, JUnit 3 `TestCase` (junit-4.0.jar on the classpath), Velocity call templates, sh + python3 scripts.

**Spec:** `docs/superpowers/specs/2026-09-21-llm-routing-2-design.md` (in this worktree, committed as 265d3ab1b).

## Global Constraints

- Work only in the scratch worktrees, never in the shared checkouts (peers work there and Tomcat loads their `build/`):
  - `W=/private/tmp/claude-501/-Users-DSANJORGE-Code-EMEGenAILabs/1ab76181-4493-4b19-ae68-748327c63194/scratchpad/wt2`
  - finder: `$W/finder` (branch `llm-routing-2`, off `origin/main` 62470bbfb), catalog: `$W/catalog` (7a10cb2), mediadb: `$W/mediadb` (a5b7285).
  - The old branch is available in each worktree as `llm-routing`; copy files with `git checkout llm-routing -- <path>` or `git show llm-routing:<path>`.
- Compile with `$W/compile.sh` (javac of the worktree finder plus the shared openedit/system/community/testu sources into `$W/build`). Run one test class with `$W/test.sh <fully.qualified.TestClass>`. Never run `bin/compile.sh` in the shared checkout and never restart Tomcat.
- No API keys anywhere in code, XML, docs or commits. Example `aiserver` rows ship with `serverapikey=""` and `disabled="true"`.
- Zero or blank numeric config means "use the default" (Elasticsearch stores a missing number as 0). Missing boolean reads false, so the flag is `disabled`, never `enabled`.
- Java style: tabs, braces on their own line, `inParam` argument names, `fieldName` members, `log` from commons-logging (matches the surrounding code).
- Nothing TestU-specific in finder/catalog/mediadb. The golden set and `harvest.py` stay in `plugins/testu` (already on its main).
- Commit after every task with a conventional-commit subject and the trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

### Task 1: Connection layer: BaseLlmConnection + OpenAiConnection merged

**Files:**
- Modify: `$W/finder/code/org/entermediadb/ai/llm/BaseLlmConnection.java` (replace with the branch version; `main` has not touched this file since the branch point)
- Modify: `$W/finder/code/org/entermediadb/ai/llm/openai/OpenAiConnection.java` (branch version, then three methods replaced below)
- Test: `$W/finder/code/org/entermediadb/ai/llm/openai/OpenAiConnectionPrepareTest.java`

**Interfaces:**
- Produces on `BaseLlmConnection`: `int getTimeoutSeconds()`, `void setTimeoutOverride(Integer)`, `CloseableHttpResponse execute(HttpRequestBase)`, `JSONObject mergeExtraParams(JSONObject)`, `void applyLlmHeaders(HttpRequestBase, Map<String,String>)`, constants `DEFAULT_TIMEOUT_SECONDS=30`, `MAX_TIMEOUT_SECONDS=1200`.
- Produces on `OpenAiConnection`: `JSONObject prepareRequest(JSONObject)`, `JSONObject loadCallPayload(AgentContext, String)`, `protected LlmResponse chat(JSONObject)`, `protected JSONObject salvageStructure(String body, JSONObject request)`, `protected static String[] LLAMA_ONLY_KEYS`.

- [ ] **Step 1: Bring over the branch versions**

```bash
cd $W/finder && git checkout llm-routing -- code/org/entermediadb/ai/llm/BaseLlmConnection.java code/org/entermediadb/ai/llm/openai/OpenAiConnection.java code/org/entermediadb/ai/llm/openai/OpenAiConnectionPrepareTest.java && git status --short
```

Expected: three files listed as modified/added. `OpenAiConnection.java` now lacks `main`'s `stripLlamaExtensions`, `salvageStructure` and the 429 retry; steps 3–5 put their behaviour back.

- [ ] **Step 2: Add the failing tests**

Append inside `OpenAiConnectionPrepareTest` (before the final `}`):

```java
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
```

- [ ] **Step 3: Run the test class to see it fail**

```bash
$W/compile.sh 2>&1 | grep -E 'error' | head
```

Expected: compile errors naming `salvageStructure` (undefined in the branch version).

- [ ] **Step 4: Replace `prepareRequest` and `LLAMA_ONLY_KEYS` in `OpenAiConnection`**

Replace the existing `LLAMA_ONLY_KEYS` constant and `prepareRequest` method with:

```java
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
```

- [ ] **Step 5: Replace `chat` and add `salvageStructure` in `OpenAiConnection`**

Replace the existing `chat(JSONObject)` method with these two methods:

```java
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
```

- [ ] **Step 6: Compile and run the tests**

```bash
$W/compile.sh 2>&1 | grep -E 'error' | head; $W/test.sh org.entermediadb.ai.llm.openai.OpenAiConnectionPrepareTest
```

Expected: no compile errors; `OK (8 tests)`.

- [ ] **Step 7: Confirm the callers all end in `chat`**

```bash
grep -n 'sharedExecute\|callJson("/chat' $W/finder/code/org/entermediadb/ai/llm/openai/OpenAiConnection.java
```

Expected: only `callRagFunction`'s `callJson("/chat/completions", obj)` (keeps `callJson`, which itself now goes through `execute`). No `sharedExecute` left in this file.

- [ ] **Step 8: Commit**

```bash
cd $W/finder && git add code/org/entermediadb/ai/llm/BaseLlmConnection.java code/org/entermediadb/ai/llm/openai/ && git commit -q -m "feat(llm): per-row timeout and extraparams, one chat() path with 429 retry and json_validate_failed salvage

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && git log --oneline -1
```

---

### Task 2: Anthropic adapter and bean

**Files:**
- Create: `$W/finder/code/org/entermediadb/ai/llm/anthropic/AnthropicConnection.java` (from branch, verbatim)
- Test: `$W/finder/code/org/entermediadb/ai/llm/anthropic/AnthropicConnectionTest.java` (from branch, verbatim)
- Modify: `$W/finder/html/src/plugin.xml` (bean after `openaiConnection`)

**Interfaces:**
- Consumes: `OpenAiConnection.chat`, `execute`, `applyLlmHeaders`, `createResponse` from Task 1.
- Produces: bean `anthropicConnection` (`connectionbean` value for `aiserver` rows), `AnthropicConnection.toAnthropicRequest/toOpenAiResponse/isRefusal` (static).

- [ ] **Step 1: Copy the adapter and its test**

```bash
cd $W/finder && git checkout llm-routing -- code/org/entermediadb/ai/llm/anthropic/ && ls code/org/entermediadb/ai/llm/anthropic/
```

Expected: `AnthropicConnection.java  AnthropicConnectionTest.java`.

- [ ] **Step 2: Compile and run the test**

```bash
$W/compile.sh 2>&1 | grep -E 'error' | head; $W/test.sh org.entermediadb.ai.llm.anthropic.AnthropicConnectionTest
```

Expected: `OK (9 tests)`.

- [ ] **Step 3: Register the bean**

In `$W/finder/html/src/plugin.xml`, directly after the closing `</bean>` of `<bean id="openaiConnection" ...>`, insert:

```xml
	<bean id="anthropicConnection" class="org.entermediadb.ai.llm.anthropic.AnthropicConnection" scope="prototype">
		<property name="moduleManager">
			<ref bean="moduleManager" />
		</property>
		<property name="requestUtils">
			<ref bean="requestUtils" />
		</property>
		<property name="pageManager">
			<ref bean="pageManager" />
		</property>
	</bean>
```

Check: `grep -n -A3 'id="openaiConnection"' $W/finder/html/src/plugin.xml` shows the same three properties on `openaiConnection`; if it has more or fewer, mirror them exactly.

- [ ] **Step 4: Validate the XML**

```bash
xmllint --noout $W/finder/html/src/plugin.xml && grep -c 'anthropicConnection' $W/finder/html/src/plugin.xml
```

Expected: no output from xmllint, count `1`.

- [ ] **Step 5: Commit**

```bash
cd $W/finder && git add code/org/entermediadb/ai/llm/anthropic/ html/src/plugin.xml && git commit -q -m "feat(llm): Anthropic Messages API adapter behind the OpenAI call templates

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && git log --oneline -1
```

---

### Task 3: FailoverLlmConnection: airoute, route timeout, timeout status, aicalllog rows

**Files:**
- Modify: `$W/finder/code/org/entermediadb/ai/llm/FailoverLlmConnection.java`
- Modify: `$W/finder/code/org/entermediadb/asset/MediaArchive.java:3409-3457` (`getLlmConnection`)
- Test: `$W/finder/code/org/entermediadb/ai/llm/FailoverLlmConnectionTest.java` (new)

**Interfaces:**
- Consumes: `BaseLlmConnection.setTimeoutOverride/getTimeoutSeconds` (Task 1).
- Produces: `setMediaArchive(MediaArchive)`, `protected Data route(String)`, `protected Entry entryFor(String)`, `protected List<Entry> chain(Data)`, `protected Integer routeTimeout(Data)`, `protected void logAttempt(Data, String, String, long, int, String, LlmResponse)`, `protected void fillLogRow(Data row, Data server, String function, String status, long ms, int attempt, String error, LlmResponse response)`, `public static Integer httpStatusOf(String)`, `protected boolean isTimeout(Throwable)`. Statuses: `ok`, `error`, `timeout`, `breakeropen`.

- [ ] **Step 1: Write the failing test**

Create `$W/finder/code/org/entermediadb/ai/llm/FailoverLlmConnectionTest.java`:

```java
package org.entermediadb.ai.llm;

import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.llm.openai.OpenAiResponse;
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
	Map<String, Data> extraServers = new HashMap<String, Data>();
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
	}

	protected String text(LlmResponse inResponse)
	{
		return (String) inResponse.getResponsePayload().get("message");
	}

	/** Ladder a then b; airoute lookups answer routeRow; log rows are collected as "server:status". */
	protected FailoverLlmConnection router(Data... inServers)
	{
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
		a.then("timeout");
		b.then("otra vez b");
		assertEquals("otra vez b", text(router(serverA, serverB).callStructure(null, "fn")));
		assertEquals(Arrays.asList("a:timeout", "b:ok"), logged);
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
}
```

- [ ] **Step 2: Compile to see it fail**

```bash
$W/compile.sh 2>&1 | grep -E 'error' | head -5
```

Expected: errors naming `route`, `logAttempt`, `httpStatusOf`, `fillLogRow` on `FailoverLlmConnection`.

- [ ] **Step 3: Extend `FailoverLlmConnection`**

Add imports:

```java
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.conn.ConnectTimeoutException;
import org.entermediadb.asset.MediaArchive;
import org.openedit.data.Searcher;
```

Update the class Javadoc's last paragraph to:

```java
 * Breaker per record: after breakerfailures consecutive failures (default 3) the record is skipped
 * for breakerminutes (default 2), then one call is let through again. State is in memory only.
 *
 * An airoute row whose id is the function name (call template) overrides the order: its aiservers
 * are tried in that order (disabled or unknown ids skipped; a row outside this type's ladder is
 * loaded on demand) and its timeoutseconds, when set, is the socket timeout for every attempt.
 * Every attempt is one aicalllog row (ok, error, timeout, breakeropen). Both need a MediaArchive;
 * without one (unit tests) the ladder alone is used and nothing is logged.
```

After `protected List<Entry> fieldEntries = new ArrayList<Entry>();` add:

```java
	protected MediaArchive fieldMediaArchive;
	// Rows an airoute names that are not in this type's ladder: built on demand, kept for the life of this connection.
	protected Map<String, Entry> fieldExtraEntries = new ConcurrentHashMap<String, Entry>();
	private static final Pattern HTTP_STATUS = Pattern.compile("\\bHTTP (\\d{3})\\b");

	public void setMediaArchive(MediaArchive inArchive)
	{
		fieldMediaArchive = inArchive;
	}

	public MediaArchive getMediaArchive()
	{
		return fieldMediaArchive;
	}
```

Replace `nextUsable`, `shouldTry`, `nextToTry` and `first()` so they take the chain as a parameter:

```java
	protected Entry nextUsable(List<Entry> inChain, int inFrom, long inNow)
	{
		for (int i = inFrom; i < inChain.size(); i++)
		{
			Entry entry = inChain.get(i);
			if (entry.connection != null && !entry.isOpen(inNow))
			{
				return entry;
			}
		}
		return null;
	}

	/**
	 * An open breaker only skips a record while a closed one is still left after it; with nothing
	 * to fall to, the record is tried anyway, so a single-record type behaves exactly as before.
	 */
	protected boolean shouldTry(List<Entry> inChain, int inIndex, long inNow)
	{
		Entry entry = inChain.get(inIndex);
		return entry.connection != null && (!entry.isOpen(inNow) || nextUsable(inChain, inIndex + 1, inNow) == null);
	}

	protected Entry nextToTry(List<Entry> inChain, int inFrom, long inNow)
	{
		for (int i = inFrom; i < inChain.size(); i++)
		{
			if (shouldTry(inChain, i, inNow))
			{
				return inChain.get(i);
			}
		}
		return null;
	}

	/** The record a plain getter (key, root, model) answers for: the one a ladder call would go to right now. */
	protected LlmConnection first()
	{
		Entry entry = nextUsable(fieldEntries, 0, System.currentTimeMillis());
		if (entry == null)
		{
			for (Entry candidate : fieldEntries)
			{
				if (candidate.connection != null)
				{
					entry = candidate;
					break;
				}
			}
		}
		if (entry == null)
		{
			throw new OpenEditException("llm " + fieldServerType + ": no usable aiserver");
		}
		return entry.connection;
	}
```

Insert the route helpers after `first()`:

```java
	// ---- airoute: per-function order and timeout ----

	/** The airoute row for a function, or null. Uncached: an admin edit takes effect on the next call. */
	protected Data route(String inFunction)
	{
		if (fieldMediaArchive == null || inFunction == null)
		{
			return null;
		}
		try
		{
			return (Data) fieldMediaArchive.getSearcher("airoute").searchById(inFunction);
		}
		catch (Exception ex)
		{
			log.error("airoute lookup failed for " + inFunction, ex);
			return null;
		}
	}

	/** The ladder entry with this id, else one built from the aiserver row (null when unknown or without an archive). */
	protected Entry entryFor(String inServerId)
	{
		for (Entry entry : fieldEntries)
		{
			if (entry.getId().equals(inServerId))
			{
				return entry;
			}
		}
		Entry extra = fieldExtraEntries.get(inServerId);
		if (extra != null || fieldMediaArchive == null)
		{
			return extra;
		}
		Data server = fieldMediaArchive.getData("aiserver", inServerId);
		if (server == null)
		{
			return null;
		}
		LlmConnection connection = null;
		try
		{
			connection = (LlmConnection) fieldMediaArchive.getModuleManager().getBean(fieldMediaArchive.getCatalogId(), server.get("connectionbean"), false);
			connection.setAiServerData(server);
		}
		catch (Exception ex)
		{
			log.warn("llm " + fieldServerType + ": airoute names " + inServerId + " but bean " + server.get("connectionbean") + " failed to load: " + ex.getMessage());
		}
		extra = new Entry(server, connection);
		Entry raced = fieldExtraEntries.putIfAbsent(inServerId, extra);
		return raced == null ? extra : raced;
	}

	/** The entries to try, in order: the route's aiservers (disabled and unknown ids skipped) or the ladder. */
	protected List<Entry> chain(Data inRoute)
	{
		if (inRoute != null)
		{
			Collection ids = inRoute.getValues("aiservers");
			if (ids != null && !ids.isEmpty())
			{
				List<Entry> chain = new ArrayList<Entry>();
				for (Object id : ids)
				{
					Entry entry = entryFor(String.valueOf(id));
					if (entry != null && !Boolean.parseBoolean(entry.server.get("disabled")))
					{
						chain.add(entry);
					}
				}
				if (!chain.isEmpty())
				{
					return chain;
				}
			}
		}
		return fieldEntries;
	}

	/** The route's timeoutseconds when above zero, else null (each row's own timeout applies). */
	protected Integer routeTimeout(Data inRoute)
	{
		if (inRoute == null)
		{
			return null;
		}
		int seconds = intValue(inRoute, "timeoutseconds", 0);
		return seconds > 0 ? Integer.valueOf(seconds) : null;
	}

	protected boolean isTimeout(Throwable inThrowable)
	{
		Throwable t = inThrowable;
		int depth = 0;
		while (t != null && depth++ < 10)
		{
			if (t instanceof SocketTimeoutException || t instanceof ConnectTimeoutException)
			{
				return true;
			}
			t = t.getCause();
		}
		return false;
	}
```

Replace `failover(...)` with:

```java
	protected LlmResponse failover(String inWhat, Call inCall)
	{
		Data route = route(inWhat);
		List<Entry> chain = chain(route);
		Integer timeout = routeTimeout(route);
		Exception last = null;
		String lastReason = "no usable aiserver";
		for (int i = 0; i < chain.size(); i++)
		{
			Entry entry = chain.get(i);
			long now = System.currentTimeMillis();
			int attempt = i + 1;
			if (!shouldTry(chain, i, now))
			{
				if (entry.connection != null)
				{
					logAttempt(entry.server, inWhat, "breakeropen", 0, attempt, null, null);
				}
				continue;
			}
			String status = "error";
			String error = "empty response";
			try
			{
				if (entry.connection instanceof BaseLlmConnection)
				{
					((BaseLlmConnection) entry.connection).setTimeoutOverride(timeout);
				}
				LlmResponse response = inCall.run(entry.connection);
				if (response != null && (response.getRawResponse() != null || response.getRawCollection() != null))
				{
					entry.succeeded();
					logAttempt(entry.server, inWhat, "ok", System.currentTimeMillis() - now, attempt, null, response);
					return response;
				}
			}
			catch (Exception ex)
			{
				last = ex;
				status = isTimeout(ex) ? "timeout" : "error";
				error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			}
			finally
			{
				if (entry.connection instanceof BaseLlmConnection)
				{
					((BaseLlmConnection) entry.connection).setTimeoutOverride(null);
				}
			}
			lastReason = error.split("\n")[0].trim();
			if (lastReason.length() > 120)
			{
				lastReason = lastReason.substring(0, 120) + "...";
			}
			logAttempt(entry.server, inWhat, status, System.currentTimeMillis() - now, attempt, error, null);
			if (entry.failed(now))
			{
				log.info("llm " + fieldServerType + ": breaker open for " + entry.getId() + " until " + new Date(entry.openUntil));
			}
			Entry next = nextToTry(chain, i + 1, now);
			if (next != null)
			{
				log.info("llm " + fieldServerType + ": " + entry.getId() + " failed (" + lastReason + ") -> trying " + next.getId());
			}
		}
		log.error("llm " + fieldServerType + ": all providers failed for " + inWhat + " (" + lastReason + ")");
		if (last instanceof RuntimeException)
		{
			throw (RuntimeException) last;
		}
		if (last != null)
		{
			throw new OpenEditException(last);
		}
		throw new OpenEditException("llm " + fieldServerType + ": all providers failed for " + inWhat + " (" + lastReason + ")");
	}
```

Delete the now-unused `shortReason(Throwable)` method. Then add the log helpers after `failover`:

```java
	// ---- aicalllog ----

	public static Integer httpStatusOf(String inMessage)
	{
		if (inMessage == null)
		{
			return null;
		}
		Matcher m = HTTP_STATUS.matcher(inMessage);
		return m.find() ? Integer.valueOf(m.group(1)) : null;
	}

	/** Pure row-filling logic, split from logAttempt so it can be unit-tested without a Searcher. Never a key. */
	protected void fillLogRow(Data inRow, Data inServer, String inFunction, String inStatus, long inMs, int inAttempt, String inError, LlmResponse inResponse)
	{
		inRow.setValue("functionname", inFunction == null ? fieldServerType : inFunction);
		inRow.setValue("aiserver", inServer.getId());
		inRow.setValue("modelname", inServer.get("modelname"));
		inRow.setValue("status", inStatus);
		inRow.setValue("ms", inMs);
		inRow.setValue("attempt", inAttempt);
		inRow.setValue("datecreated", new Date());
		if (inError != null)
		{
			inRow.setValue("errormessage", inError.length() > 500 ? inError.substring(0, 500) : inError);
			Integer http = httpStatusOf(inError);
			if (http != null)
			{
				inRow.setValue("httpstatus", http);
			}
		}
		if (inResponse != null && inResponse.getRawResponse() != null)
		{
			JSONObject usage = (JSONObject) inResponse.getRawResponse().get("usage");
			if (usage != null)
			{
				inRow.setValue("prompttokens", usage.get("prompt_tokens"));
				inRow.setValue("completiontokens", usage.get("completion_tokens"));
			}
		}
	}

	/** One aicalllog row. Never throws: a failed write is a log line, not a failed answer. */
	protected void logAttempt(Data inServer, String inFunction, String inStatus, long inMs, int inAttempt, String inError, LlmResponse inResponse)
	{
		if (fieldMediaArchive == null)
		{
			return;
		}
		try
		{
			Searcher searcher = fieldMediaArchive.getSearcher("aicalllog");
			Data row = searcher.createNewData();
			fillLogRow(row, inServer, inFunction, inStatus, inMs, inAttempt, inError, inResponse);
			searcher.saveData(row, null);
		}
		catch (Throwable ex)
		{
			log.error("aicalllog write failed", ex);
		}
	}
```

- [ ] **Step 4: Compile and run the router test**

```bash
$W/compile.sh 2>&1 | grep -E 'error' | head; $W/test.sh org.entermediadb.ai.llm.FailoverLlmConnectionTest
```

Expected: `OK (12 tests)`. If `intValue` is not visible from the test, it is `protected static` in the same package: fine. If `Entry.server` is not visible, both classes are in `org.entermediadb.ai.llm`: fine.

- [ ] **Step 5: Wire MediaArchive**

In `$W/finder/code/org/entermediadb/asset/MediaArchive.java` `getLlmConnection`, after the loop that fills `servers` and before `if (servers.isEmpty())`, insert:

```java
			if ("embedding".equals(inServerType) && servers.size() > 1)
			{
				// Vectors from two models are not comparable, so an embedding ladder never fails over:
				// keep the first row only. An airoute row can still name a different server.
				servers = new ArrayList<Data>(servers.subList(0, 1));
			}
```

And right after `FailoverLlmConnection failover = new FailoverLlmConnection(inServerType);` insert:

```java
			failover.setMediaArchive(this);
```

- [ ] **Step 6: Compile everything and rerun all three test classes**

```bash
$W/compile.sh 2>&1 | grep -E 'error' | head; for t in org.entermediadb.ai.llm.FailoverLlmConnectionTest org.entermediadb.ai.llm.openai.OpenAiConnectionPrepareTest org.entermediadb.ai.llm.anthropic.AnthropicConnectionTest; do $W/test.sh $t | tail -2; done
```

Expected: three `OK (...)` lines.

- [ ] **Step 7: Commit**

```bash
cd $W/finder && git add code/org/entermediadb/ai/llm/FailoverLlmConnection.java code/org/entermediadb/ai/llm/FailoverLlmConnectionTest.java code/org/entermediadb/asset/MediaArchive.java && git commit -q -m "feat(llm): airoute per-function order and timeout, aicalllog row per attempt on FailoverLlmConnection

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && git log --oneline -1
```

---

### Task 4: Catalog data: airoute, aicalllog, llmtype, example rows, cleanup event

**Files:**
- Create: `$W/catalog/html/data/fields/airoute.xml` (from branch, verbatim)
- Create: `$W/catalog/html/data/fields/aicalllog.xml` (from branch, verbatim)
- Modify: `$W/catalog/html/data/lists/llmtype.xml`
- Modify: `$W/catalog/html/data/lists/aiserver/baseaiserver.xml`
- Create: `$W/catalog/html/events/llm/cleanaicalllog.xconf` (from branch, verbatim)
- Create: `$W/catalog/html/events/scripts/llm/cleanaicalllog.groovy`
- Create: `$W/finder/code/org/entermediadb/ai/llm/AiCallLogCleaner.java`

**Interfaces:**
- Produces: tables `airoute` (id = function, `aiservers` multi, `timeoutseconds`, `description`) and `aicalllog` (fields used by `fillLogRow` in Task 3); `AiCallLogCleaner.deleteOlderThanDays(MediaArchive, int)`.

- [ ] **Step 1: Copy the field definitions and the event**

```bash
cd $W/catalog && git checkout llm-routing -- html/data/fields/airoute.xml html/data/fields/aicalllog.xml html/events/llm/cleanaicalllog.xconf && git status --short
```

Expected: three new files. Do NOT check out the branch's `aiserver.xml` (main's version already has every field; the branch's says breaker default 5, main says 2).

- [ ] **Step 2: Add `anthropic` to `llmtype.xml`**

Insert before the closing `</llm>` of `$W/catalog/html/data/lists/llmtype.xml`:

```xml
  <llm id="anthropic">
   Anthropic Messages API
  </llm>

```

- [ ] **Step 3: Add the disabled example rows to `baseaiserver.xml`**

Insert before the closing `</root>`:

```xml
  <data id="anthropic" connectionbean="anthropicConnection" modelname="claude-sonnet-5" serverroot="https://api.anthropic.com" serverpathprefix="/v1" serverapikey="" disabled="true" ordering="50" aiservertype="thinking" extraparams='{"thinking":{"type":"disabled"}}'>
    <name><![CDATA[AI: Anthropic Claude]]></name>
  </data>
  <data id="groq" connectionbean="openaiConnection" modelname="openai/gpt-oss-120b" serverroot="https://api.groq.com/openai" serverpathprefix="/v1" serverapikey="" disabled="true" ordering="20" aiservertype="thinking" extraparams='{"max_tokens":2000,"reasoning_effort":"low"}'>
    <name><![CDATA[AI: Groq (OpenAI compatible)]]></name>
  </data>
  <data id="openrouter" connectionbean="openaiConnection" modelname="openai/gpt-oss-120b" serverroot="https://openrouter.ai/api" serverpathprefix="/v1" serverapikey="" disabled="true" ordering="15" aiservertype="thinking">
    <name><![CDATA[AI: OpenRouter (OpenAI compatible)]]></name>
  </data>
```

- [ ] **Step 4: Write the groovy script and the cleaner**

`$W/catalog/html/events/scripts/llm/cleanaicalllog.groovy`:

```groovy
import org.entermediadb.ai.llm.AiCallLogCleaner
import org.entermediadb.asset.MediaArchive

public void init() {

	MediaArchive archive = context.getPageValue("mediaarchive");
	int deleted = AiCallLogCleaner.deleteOlderThanDays(archive, 30);
	log.info("aicalllog cleanup deleted " + deleted + " rows");

}


init();
```

`$W/finder/code/org/entermediadb/ai/llm/AiCallLogCleaner.java`:

```java
package org.entermediadb.ai.llm;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.entermediadb.asset.MediaArchive;
import org.openedit.Data;
import org.openedit.hittracker.HitTracker;

/** Run daily by events/llm/cleanaicalllog.xconf: aicalllog rows older than N days are deleted. */
public class AiCallLogCleaner
{
	public static int deleteOlderThanDays(MediaArchive inArchive, int inDays)
	{
		Calendar cutoff = Calendar.getInstance();
		cutoff.add(Calendar.DAY_OF_YEAR, -inDays);
		HitTracker hits = inArchive.query("aicalllog").before("datecreated", cutoff.getTime()).search();
		List<Data> rows = new ArrayList<Data>();
		for (Object hit : hits)
		{
			rows.add((Data) hit);
		}
		if (!rows.isEmpty())
		{
			inArchive.getSearcher("aicalllog").deleteAll(rows, null);
		}
		return rows.size();
	}
}
```

- [ ] **Step 5: Validate XML and compile**

```bash
for f in $W/catalog/html/data/fields/airoute.xml $W/catalog/html/data/fields/aicalllog.xml $W/catalog/html/data/lists/llmtype.xml $W/catalog/html/data/lists/aiserver/baseaiserver.xml $W/catalog/html/events/llm/cleanaicalllog.xconf; do xmllint --noout "$f" && echo "ok $f"; done; grep -c 'serverapikey=""' $W/catalog/html/data/lists/aiserver/baseaiserver.xml; $W/compile.sh 2>&1 | grep -E 'error' | head
```

Expected: five `ok` lines, count `3`, no compile errors.

- [ ] **Step 6: Commit both repos**

```bash
cd $W/catalog && git add html/data/fields/airoute.xml html/data/fields/aicalllog.xml html/data/lists/llmtype.xml html/data/lists/aiserver/baseaiserver.xml html/events/llm/cleanaicalllog.xconf html/events/scripts/llm/cleanaicalllog.groovy && git commit -q -m "feat(ai): airoute and aicalllog tables, anthropic llmtype, disabled example rows, daily log cleanup

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && git log --oneline -1
cd $W/finder && git add code/org/entermediadb/ai/llm/AiCallLogCleaner.java && git commit -q -m "feat(llm): AiCallLogCleaner for the daily aicalllog cleanup event

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && git log --oneline -1
```

---

### Task 5: Eval endpoint, judge template, harness

**Files:**
- Create: `$W/finder/code/org/entermediadb/ai/llm/LlmEvalModule.java` (branch file, package changed from `...llm.router` to `...llm`)
- Modify: `$W/finder/html/src/plugin.xml` (bean `LlmEvalModule`)
- Create: `$W/finder/tools/llmeval/{run.sh,judge.sh,report.sh,prices.json}` (from branch, verbatim)
- Create: `$W/mediadb/html/services/llm/evalcall.xconf`, `$W/mediadb/html/services/llm/evalcall.json`, `$W/mediadb/html/ai/default/calls/llm_eval_judge.json` (from branch, verbatim)

**Interfaces:**
- Consumes: `OpenAiConnection.loadCallPayload` (Task 1) for `dryrun`.
- Produces: `POST {mediadb}/services/llm/evalcall.json` with `function`, `aiserver`, `input` (JSON), optional `dryrun=true`; answers `{ok, aiserver, function, model, message, payload, usage, ms}` or `{ok:false, error}`; 403 for non-administrators, 400 for a missing/unknown/disabled `aiserver`.

- [ ] **Step 1: Copy the module into the new package**

```bash
cd $W/finder && git show llm-routing:code/org/entermediadb/ai/llm/router/LlmEvalModule.java | sed 's/^package org.entermediadb.ai.llm.router;/package org.entermediadb.ai.llm;/' > code/org/entermediadb/ai/llm/LlmEvalModule.java && head -3 code/org/entermediadb/ai/llm/LlmEvalModule.java && git checkout llm-routing -- tools/llmeval/ && ls tools/llmeval/
```

Expected: `package org.entermediadb.ai.llm;` and four files `judge.sh prices.json report.sh run.sh`.

- [ ] **Step 2: Register the bean**

In `$W/finder/html/src/plugin.xml`, directly after the `anthropicConnection` bean from Task 2, insert:

```xml
	<bean id="LlmEvalModule" class="org.entermediadb.ai.llm.LlmEvalModule" scope="prototype">
		<property name="moduleManager">
			<ref bean="moduleManager" />
		</property>
	</bean>
```

- [ ] **Step 3: Copy the mediadb files**

```bash
cd $W/mediadb && git checkout llm-routing -- html/services/llm/evalcall.xconf html/services/llm/evalcall.json html/ai/default/calls/llm_eval_judge.json && git status --short && cat html/services/llm/evalcall.xconf
```

Expected: three new files; the xconf names `LlmEvalModule.evalCall` with a blank `view` permission (the module itself enforces administrators).

- [ ] **Step 4: Compile, validate, and dry-run the shell scripts' syntax**

```bash
$W/compile.sh 2>&1 | grep -E 'error' | head; xmllint --noout $W/finder/html/src/plugin.xml $W/mediadb/html/services/llm/evalcall.xconf && for s in run.sh judge.sh report.sh; do sh -n $W/finder/tools/llmeval/$s && echo "syntax ok $s"; done; python3 -c "import json; json.load(open('$W/finder/tools/llmeval/prices.json'))" && echo prices ok
```

Expected: no compile errors, three `syntax ok`, `prices ok`.

- [ ] **Step 5: Commit both repos**

```bash
cd $W/finder && git add code/org/entermediadb/ai/llm/LlmEvalModule.java html/src/plugin.xml tools/llmeval/ && git commit -q -m "feat(llm): admin-only evalcall endpoint and llmeval harness (run, judge, report)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && git log --oneline -1
cd $W/mediadb && git add html/services/llm/ html/ai/default/calls/llm_eval_judge.json && git commit -q -m "feat(ai): services/llm/evalcall.json and the generic llm_eval_judge template

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && git log --oneline -1
```

---

### Task 6: Whole-branch check, push, PRs

**Files:** none new. Verifies the three worktrees together.

- [ ] **Step 1: Full compile and every test**

```bash
$W/compile.sh 2>&1 | grep -E 'error' | head; for t in org.entermediadb.ai.llm.FailoverLlmConnectionTest org.entermediadb.ai.llm.openai.OpenAiConnectionPrepareTest org.entermediadb.ai.llm.anthropic.AnthropicConnectionTest; do $W/test.sh $t | tail -1; done
```

Expected: three `OK` lines and no compile errors.

- [ ] **Step 2: No leftovers, no keys**

```bash
cd $W/finder && git diff origin/main --stat | tail -1; git grep -n 'RoutedLlmConnection\|ai.llm.router' -- code html | head; git grep -n -i 'sk-ant-\|gsk_' -- . | head
```

Expected: no matches for `RoutedLlmConnection`/`ai.llm.router` and no key-shaped strings.

- [ ] **Step 3: Push the three branches**

```bash
for p in finder catalog mediadb; do (cd $W/$p && git push -q -u origin llm-routing-2 && echo "pushed $p $(git rev-parse --short HEAD)"); done
```

- [ ] **Step 4: Open the PRs against each fork's `main`**

For each of `finder`, `catalog`, `mediadb` run `gh pr create --base main --head llm-routing-2 --title "LLM routing 2: airoute, aicalllog, Anthropic adapter, eval harness on FailoverLlmConnection" --body-file <file>` from `$W/<repo>`, where the body says: what the branch adds on top of `main`'s `FailoverLlmConnection` (the six pieces, one line each), that it supersedes PR #1 of the same repo, the local test command, the rollout steps (Reindex `aiserver`/`aicalllog`/`airoute` after deploy; example rows disabled with blank keys), and ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. Report the three PR URLs. Do not close the old PRs: the user decides.
