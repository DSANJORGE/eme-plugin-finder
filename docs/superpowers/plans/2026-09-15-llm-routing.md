# LLM Provider Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let each catalog pick which LLM servers answer each AI function, in order, with automatic failover, a circuit breaker, per-attempt logging, and an eval harness to compare providers.

**Architecture:** `MediaArchive.getLlmConnection(type)` returns a `RoutedLlmConnection` that wraps one real connection bean per enabled `aiserver` row and walks the chain on failure. Every provider takes the same OpenAI-shaped Velocity template; `OpenAiConnection` serves OpenAI-compatible hosts (Groq, OpenRouter, Together, Cerebras, llama.cpp) and a new `AnthropicConnection` translates to the Messages API. Config is data: `aiserver` rows, `airoute` rows, `aicalllog` rows, all per catalog.

**Tech Stack:** Java 21 (EnterMedia plugins finder, catalog, mediadb), Elasticsearch-backed `Data` tables, Apache HttpClient 4.5 via `HttpSharedConnection`, json-simple, JUnit 3 style `TestCase` (junit-4.0.jar), Velocity call templates, sh + python3 eval scripts.

**Spec:** `plugins/finder/docs/superpowers/specs/2026-09-15-llm-routing-design.md`

## Global Constraints

- Three git repos, each committed separately: `plugins/finder` (Java, tests, harness runner), `plugins/catalog` (fields, lists, events), `plugins/mediadb` (eval endpoint, judge template). Working tree root: `/Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur`.
- No new jar dependencies. Anthropic goes over raw HTTP through `HttpSharedConnection`.
- No API keys in any file, commit, log row, exception message, or admin list. Example `aiserver` rows ship with blank keys and `enabled=false`.
- Nothing TestU-specific in finder/catalog/mediadb code. The TestU golden set lives in `plugins/testu/tools/llmeval/`.
- Default per-row socket timeout 30 s; ceiling 1200 s. Breaker defaults: 3 consecutive failures open it for 5 minutes.
- `aicalllog.errormessage` is cut to 500 characters. Cleanup deletes rows older than 30 days.
- Java style: tabs, braces on their own line, `fieldX` members with getters/setters, `inX` parameters, as in `OpenAiConnection.java`.
- Do not run `javac` by hand. Compile with `bin/compile.sh` from the repo root (it writes `build/`, which the running Tomcat loads, so tell peers before restarting Tomcat, per the shared-Tomcat rule).
- Tests are plain JUnit 3 `TestCase` classes under `plugins/finder/code/...` with no server fixture. Run one with:

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/compile.sh >/dev/null && java -cp "build:$(find plugins/system/lib plugins/finder/lib plugins/community/lib -name '*.jar' | tr '\n' ':')" junit.textui.TestRunner <fully.qualified.TestClass>
```

- Velocity call templates are resolved at `/{mediadbid}/ai/{protocol}/calls/{function}.json`; `ai/llama` falls back to `ai/openai`, which falls back to `ai/default` (`plugins/mediadb/html/ai/*/_site.xconf`). `AnthropicConnection.getLlmProtocol()` returns `openai` so it uses the same templates with no new fallback directory.
- Local checks run as `admin`/`admin` against `http://localhost:8080/site/mediadb` with the cookie flow in `plugins/testu/tools/check_ask.sh`. Never log in as `diego`.

## Spec deviations settled here

- The `X-AiServer` header is dropped. The tutor's LLM call runs on the `monitorchats` event thread, not the HTTP request thread, so a request header cannot reach the router. A/B for normal traffic is the `airoute` row; single-server calls for the harness use the `aiserver` parameter of the eval endpoint.
- The eval endpoint is `services/llm/evalcall.json` in the mediadb plugin (`services/ai/` is a virtual directory owned by `JsonDataModule.handleAiFunction`).
- `aicalllog` has no `user` column: the event thread has no request user.
- Router instances are rebuilt when older than 60 s instead of relying on cache clears, because nothing clears the `llmconnection` cache when an admin edits `aiserver` or `airoute` rows.

## File structure

| File | Responsibility |
|---|---|
| `plugins/catalog/html/data/fields/aiserver.xml` | + `enabled`, `timeoutseconds`, `breakerfailures`, `breakerminutes`, `healthms`, `extraparams` |
| `plugins/catalog/html/data/fields/airoute.xml` (new) | per-function chain |
| `plugins/catalog/html/data/fields/aicalllog.xml` (new) | per-attempt log |
| `plugins/catalog/html/data/lists/llmtype.xml` | + `anthropic` |
| `plugins/catalog/html/data/lists/aiserver/baseaiserver.xml` | example rows groq, openrouter, together, anthropic (disabled, blank keys) |
| `plugins/catalog/html/events/llm/cleanaicalllog.xconf` + `events/scripts/llm/cleanaicalllog.groovy` (new) | daily cleanup |
| `plugins/finder/code/org/entermediadb/ai/llm/BaseLlmConnection.java` | per-row timeout, `extraparams` merge, `execute()` |
| `plugins/finder/code/org/entermediadb/ai/llm/openai/OpenAiConnection.java` | `loadCallPayload`, `prepareRequest` (strip llama-only keys), `chat` |
| `plugins/finder/code/org/entermediadb/ai/llm/openai/OpenAiConnectionPrepareTest.java` (new) | tests |
| `plugins/finder/code/org/entermediadb/ai/llm/anthropic/AnthropicConnection.java` (new) | Messages API adapter |
| `plugins/finder/code/org/entermediadb/ai/llm/anthropic/AnthropicConnectionTest.java` (new) | tests |
| `plugins/finder/code/org/entermediadb/ai/llm/router/RoutedLlmConnection.java` (new) | chain, breaker, failover, log rows |
| `plugins/finder/code/org/entermediadb/ai/llm/router/RoutedLlmConnectionTest.java` (new) | tests |
| `plugins/finder/code/org/entermediadb/ai/llm/router/AiCallLogCleaner.java` (new) | delete old log rows |
| `plugins/finder/code/org/entermediadb/ai/llm/router/LlmEvalModule.java` (new) | admin-only single-server call endpoint |
| `plugins/finder/code/org/entermediadb/asset/MediaArchive.java` | `getLlmConnection` builds the router |
| `plugins/finder/code/org/entermediadb/ai/assistant/AssistantManager.java` | `monitorAiServers` writes `healthms`, closes breakers, header fix |
| `plugins/finder/html/src/plugin.xml` | beans `anthropicConnection`, `LlmEvalModule` |
| `plugins/mediadb/html/services/llm/evalcall.xconf` + `.json` (new) | endpoint wiring |
| `plugins/mediadb/html/ai/default/calls/llm_eval_judge.json` (new) | judge template |
| `plugins/finder/tools/llmeval/run.sh`, `judge.sh`, `report.sh`, `prices.json` (new) | harness |
| `plugins/testu/tools/llmeval/golden.jsonl` (new) | TestU golden prompts |

---

### Task 1: Catalog data model

**Files:**
- Modify: `plugins/catalog/html/data/fields/aiserver.xml`
- Create: `plugins/catalog/html/data/fields/airoute.xml`
- Create: `plugins/catalog/html/data/fields/aicalllog.xml`
- Modify: `plugins/catalog/html/data/lists/llmtype.xml`
- Modify: `plugins/catalog/html/data/lists/aiserver/baseaiserver.xml`

**Interfaces:**
- Produces: `aiserver` string fields readable via `Data.get("enabled")` (`"true"`/`"false"`/null), `get("timeoutseconds")`, `get("breakerfailures")`, `get("breakerminutes")`, `get("extraparams")`; `airoute` row id = function name, `getValues("aiservers")` ordered ids, `get("timeoutseconds")`; `aicalllog` columns listed below.

- [ ] **Step 1: Add the six fields to `aiserver.xml`**

Insert before the closing `</properties>`:

```xml
  <property id="enabled" editable="true" index="true" type="boolean" stored="true">
    <name>
      <language id="en"><![CDATA[Enabled]]></language>
      <language id="es"><![CDATA[Habilitado]]></language>
    </name>
  </property>
  <property id="timeoutseconds" editable="true" index="true" type="number" stored="true">
    <name>
      <language id="en"><![CDATA[Timeout (seconds, default 30)]]></language>
      <language id="es"><![CDATA[Tiempo límite (segundos, 30 por defecto)]]></language>
    </name>
  </property>
  <property id="breakerfailures" editable="true" index="true" type="number" stored="true">
    <name>
      <language id="en"><![CDATA[Breaker: failures to open (default 3)]]></language>
      <language id="es"><![CDATA[Fusible: fallos para abrir (3 por defecto)]]></language>
    </name>
  </property>
  <property id="breakerminutes" editable="true" index="true" type="number" stored="true">
    <name>
      <language id="en"><![CDATA[Breaker: minutes open (default 5)]]></language>
      <language id="es"><![CDATA[Fusible: minutos abierto (5 por defecto)]]></language>
    </name>
  </property>
  <property id="healthms" editable="false" index="true" type="number" stored="true">
    <name>
      <language id="en"><![CDATA[Last /health ms (-1 = failed)]]></language>
      <language id="es"><![CDATA[Último /health ms (-1 = falló)]]></language>
    </name>
  </property>
  <property id="extraparams" editable="true" index="false" stored="true" viewtype="textarea">
    <name>
      <language id="en"><![CDATA[Extra request params (JSON object merged into every call)]]></language>
      <language id="es"><![CDATA[Parámetros extra (objeto JSON añadido a cada llamada)]]></language>
    </name>
  </property>
```

- [ ] **Step 2: Create `airoute.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>

<properties beanname="folderSearcher">
  <property id="id" internalfield="true" index="true" editable="true" stored="true">
    <name>
      <language id="en"><![CDATA[Function name (call template)]]></language>
      <language id="es"><![CDATA[Nombre de función (plantilla)]]></language>
    </name>
  </property>
  <property id="name" editable="true" index="true" stored="true">
    <name>
      <language id="en"><![CDATA[Name]]></language>
      <language id="es"><![CDATA[Nombre]]></language>
    </name>
  </property>
  <property id="aiservers" editable="true" index="true" type="list" listid="aiserver" viewtype="multiselect" stored="true">
    <name>
      <language id="en"><![CDATA[AI servers, in order]]></language>
      <language id="es"><![CDATA[Servidores de IA, en orden]]></language>
    </name>
  </property>
  <property id="timeoutseconds" editable="true" index="true" type="number" stored="true">
    <name>
      <language id="en"><![CDATA[Timeout override (seconds)]]></language>
      <language id="es"><![CDATA[Tiempo límite (segundos)]]></language>
    </name>
  </property>
  <property id="description" editable="true" index="false" stored="true" viewtype="textarea">
    <name>
      <language id="en"><![CDATA[Description]]></language>
      <language id="es"><![CDATA[Descripción]]></language>
    </name>
  </property>
</properties>
```

- [ ] **Step 3: Create `aicalllog.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>

<properties beanname="folderSearcher">
  <property id="id" internalfield="true" index="true" editable="false" stored="true">
    <name><language id="en"><![CDATA[Id]]></language></name>
  </property>
  <property id="functionname" editable="false" index="true" indextype="not_analyzed" stored="true">
    <name><language id="en"><![CDATA[Function]]></language></name>
  </property>
  <property id="aiserver" editable="false" index="true" type="list" listid="aiserver" stored="true">
    <name><language id="en"><![CDATA[AI server]]></language></name>
  </property>
  <property id="modelname" editable="false" index="true" indextype="not_analyzed" stored="true">
    <name><language id="en"><![CDATA[Model]]></language></name>
  </property>
  <property id="status" editable="false" index="true" indextype="not_analyzed" stored="true">
    <name><language id="en"><![CDATA[Status]]></language></name>
  </property>
  <property id="ms" editable="false" index="true" type="number" stored="true">
    <name><language id="en"><![CDATA[Milliseconds]]></language></name>
  </property>
  <property id="attempt" editable="false" index="true" type="number" stored="true">
    <name><language id="en"><![CDATA[Attempt]]></language></name>
  </property>
  <property id="httpstatus" editable="false" index="true" type="number" stored="true">
    <name><language id="en"><![CDATA[HTTP status]]></language></name>
  </property>
  <property id="promptokens" editable="false" index="true" type="number" stored="true">
    <name><language id="en"><![CDATA[Prompt tokens]]></language></name>
  </property>
  <property id="completiontokens" editable="false" index="true" type="number" stored="true">
    <name><language id="en"><![CDATA[Completion tokens]]></language></name>
  </property>
  <property id="errormessage" editable="false" index="false" stored="true" viewtype="textarea">
    <name><language id="en"><![CDATA[Error]]></language></name>
  </property>
  <property id="datecreated" editable="false" index="true" type="date" stored="true">
    <name><language id="en"><![CDATA[Date]]></language></name>
  </property>
</properties>
```

- [ ] **Step 4: Add `anthropic` to `llmtype.xml`**

Insert before `</llm>`:

```xml
  <llm id="anthropic">
   Anthropic Messages API
  </llm>
```

- [ ] **Step 5: Add disabled example rows to `baseaiserver.xml`**

Insert before `</root>`:

```xml
  <data id="groq" connectionbean="openaiConnection" modelname="llama-3.3-70b-versatile" serverroot="https://api.groq.com/openai" serverpathprefix="/v1" serverapikey="" enabled="false" ordering="20" aiservertype="thinking">
    <name><![CDATA[AI: Groq (OpenAI compatible)]]></name>
  </data>
  <data id="openrouter" connectionbean="openaiConnection" modelname="qwen/qwen3-235b-a22b-2507" serverroot="https://openrouter.ai/api" serverpathprefix="/v1" serverapikey="" enabled="false" ordering="30" aiservertype="thinking">
    <name><![CDATA[AI: OpenRouter (OpenAI compatible)]]></name>
  </data>
  <data id="together" connectionbean="openaiConnection" modelname="meta-llama/Llama-3.3-70B-Instruct-Turbo" serverroot="https://api.together.xyz" serverpathprefix="/v1" serverapikey="" enabled="false" ordering="40" aiservertype="thinking">
    <name><![CDATA[AI: Together AI (OpenAI compatible)]]></name>
  </data>
  <data id="anthropic" connectionbean="anthropicConnection" modelname="claude-opus-5" serverroot="https://api.anthropic.com" serverpathprefix="/v1" serverapikey="" enabled="false" ordering="50" aiservertype="thinking">
    <name><![CDATA[AI: Anthropic Claude]]></name>
  </data>
```

Also add `ordering="10"` to the existing `llamat` row so it stays first.

- [ ] **Step 6: Validate the XML**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/catalog && for f in html/data/fields/aiserver.xml html/data/fields/airoute.xml html/data/fields/aicalllog.xml html/data/lists/llmtype.xml html/data/lists/aiserver/baseaiserver.xml; do xmllint --noout "$f" && echo "ok $f"; done
```

Expected: five `ok` lines.

- [ ] **Step 7: Commit (catalog repo)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/catalog && git add html/data/fields/aiserver.xml html/data/fields/airoute.xml html/data/fields/aicalllog.xml html/data/lists/llmtype.xml html/data/lists/aiserver/baseaiserver.xml && git commit -m "Add LLM routing fields: aiserver breaker/timeout, airoute, aicalllog, anthropic llmtype

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Per-row timeout, extra params, and request preparation in the OpenAI connection

**Files:**
- Modify: `plugins/finder/code/org/entermediadb/ai/llm/BaseLlmConnection.java`
- Modify: `plugins/finder/code/org/entermediadb/ai/llm/openai/OpenAiConnection.java`
- Test: `plugins/finder/code/org/entermediadb/ai/llm/openai/OpenAiConnectionPrepareTest.java`

**Interfaces:**
- Produces on `BaseLlmConnection`: `public int getTimeoutSeconds()`, `public void setTimeoutOverride(Integer inSeconds)`, `public JSONObject mergeExtraParams(JSONObject inPayload)`, `protected CloseableHttpResponse execute(HttpRequestBase inMethod)`.
- Produces on `OpenAiConnection`: `public JSONObject prepareRequest(JSONObject inPayload)`, `protected JSONObject loadCallPayload(AgentContext inContext, String inFunction)`, `protected LlmResponse chat(JSONObject inPayload)`.

- [ ] **Step 1: Write the failing test**

`plugins/finder/code/org/entermediadb/ai/llm/openai/OpenAiConnectionPrepareTest.java`:

```java
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

		c.setTimeoutOverride(7);
		assertEquals(7, c.getTimeoutSeconds());
	}
}
```

- [ ] **Step 2: Run it to see it fail**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/compile.sh 2>&1 | grep -E "error|cannot find" | head
```

Expected: compile errors naming `prepareRequest`, `getTimeoutSeconds`, `setTimeoutOverride`.

- [ ] **Step 3: Add timeout, extra params, and `execute()` to `BaseLlmConnection`**

Add imports:

```java
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.openedit.util.JSONParser;
```

Add after the `getConnection()` method:

```java
	public static final int DEFAULT_TIMEOUT_SECONDS = 30;
	public static final int MAX_TIMEOUT_SECONDS = 1200;

	protected Integer fieldTimeoutOverride;

	public void setTimeoutOverride(Integer inSeconds)
	{
		fieldTimeoutOverride = inSeconds;
	}

	/** Route override, else the aiserver row's timeoutseconds, else 30; never above 1200. */
	public int getTimeoutSeconds()
	{
		int seconds = DEFAULT_TIMEOUT_SECONDS;
		if (fieldTimeoutOverride != null)
		{
			seconds = fieldTimeoutOverride;
		}
		else if (getAiServerData() != null)
		{
			String value = getAiServerData().get("timeoutseconds");
			if (value != null && !value.trim().isEmpty())
			{
				try
				{
					seconds = Integer.parseInt(value.trim());
				}
				catch (NumberFormatException ex)
				{
					log.error("Bad timeoutseconds on aiserver " + getAiServerData().getId() + ": " + value);
				}
			}
		}
		return Math.max(1, Math.min(seconds, MAX_TIMEOUT_SECONDS));
	}

	/** Executes with this row's socket timeout instead of HttpSharedConnection's 30 s default. */
	protected CloseableHttpResponse execute(HttpRequestBase inMethod)
	{
		RequestConfig config = RequestConfig.custom()
			.setCookieSpec(CookieSpecs.STANDARD)
			.setConnectionRequestTimeout(5 * 1000)
			.setConnectTimeout(10 * 1000)
			.setSocketTimeout(getTimeoutSeconds() * 1000)
			.build();
		inMethod.setConfig(config);
		return getConnection().sharedExecute(inMethod);
	}

	/** Merges the aiserver row's extraparams JSON object (provider-specific options) into the request. */
	public JSONObject mergeExtraParams(JSONObject inPayload)
	{
		if (getAiServerData() == null)
		{
			return inPayload;
		}
		String extra = getAiServerData().get("extraparams");
		if (extra == null || extra.trim().isEmpty())
		{
			return inPayload;
		}
		JSONObject more = new JSONParser().parse(extra);
		inPayload.putAll(more);
		return inPayload;
	}
```

In `callJson(String inPath, Map<String, String> inHeaders, JSONObject inPayload)` replace

```java
		HttpSharedConnection connection = getConnection();
		CloseableHttpResponse resp = connection.sharedExecute(method);
```

with

```java
		HttpSharedConnection connection = getConnection();
		CloseableHttpResponse resp = execute(method);
```

- [ ] **Step 4: Refactor `OpenAiConnection` around `loadCallPayload`, `prepareRequest`, `chat`**

Add imports `org.apache.http.util.EntityUtils` and `java.util.Map`. Add these methods:

```java
	// Keys only llama.cpp understands; OpenAI-compatible hosts such as Groq reject unknown keys.
	protected static final String[] LLAMA_ONLY_KEYS = { "chat_template_kwargs", "cache_prompt", "slot_id", "n_probs", "min_keep" };

	public JSONObject prepareRequest(JSONObject inPayload)
	{
		if (!"llama".equals(getLlmProtocol()))
		{
			for (String key : LLAMA_ONLY_KEYS)
			{
				inPayload.remove(key);
			}
		}
		return mergeExtraParams(inPayload);
	}

	/** Renders /{mediadb}/ai/{protocol}/calls/{function}.json (catalog fallback) and prepares it. */
	protected JSONObject loadCallPayload(AgentContext inContext, String inFunction)
	{
		MediaArchive archive = getMediaArchive();
		inContext.put("model", getModelName());
		inContext.addContext("aiserver", getAiServerData());

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

	/** One chat/completions round trip. Non-200 raises with the status and the first 500 chars of the body. */
	protected LlmResponse chat(JSONObject inPayload)
	{
		log.info("Sent: " + inPayload.toJSONString());
		HttpPost method = new HttpPost(getServerRoot() + "/chat/completions");
		method.addHeader("Authorization", "Bearer " + getApiKey());
		method.setHeader("Content-Type", "application/json");
		method.setEntity(new StringEntity(inPayload.toJSONString(), StandardCharsets.UTF_8));

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
			LlmResponse response = createResponse();
			response.setRawResponse(json);
			return response;
		}
		finally
		{
			getConnection().release(resp);
		}
	}
```

Replace the bodies of these methods:

```java
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

	@Override
	public LlmResponse callStructure(AgentContext inParams, String inFunctionName)
	{
		return chat(loadCallPayload(inParams, inFunctionName));
	}
```

In `callSmartCreatorAiAction` replace everything from `log.info("Sent: ...` to the end of the method with `return chat(prepareRequest(structureDef));`. In `runPageAsInput` replace `getConnection().sharedExecute(method)` with `execute(method)`. In `callCreateFunction` replace `LlmResponse res = callJson("/chat/completions", obj);` with `LlmResponse res = chat(prepareRequest(obj));`.

Remove the now-unused `HttpPost`/`StringEntity` code paths only where the method body was replaced; keep the imports (still used by `chat`).

- [ ] **Step 5: Run the test**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/compile.sh >/dev/null && java -cp "build:$(find plugins/system/lib plugins/finder/lib plugins/community/lib -name '*.jar' | tr '\n' ':')" junit.textui.TestRunner org.entermediadb.ai.llm.openai.OpenAiConnectionPrepareTest
```

Expected: `OK (4 tests)`.

- [ ] **Step 6: Commit (finder repo)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/finder && git add code/org/entermediadb/ai/llm/BaseLlmConnection.java code/org/entermediadb/ai/llm/openai/OpenAiConnection.java code/org/entermediadb/ai/llm/openai/OpenAiConnectionPrepareTest.java && git commit -m "LLM: per-row socket timeout, extraparams merge, strip llama-only keys for OpenAI-compatible hosts

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Anthropic adapter

**Files:**
- Create: `plugins/finder/code/org/entermediadb/ai/llm/anthropic/AnthropicConnection.java`
- Test: `plugins/finder/code/org/entermediadb/ai/llm/anthropic/AnthropicConnectionTest.java`
- Modify: `plugins/finder/html/src/plugin.xml` (bean `anthropicConnection`)

**Interfaces:**
- Consumes: `OpenAiConnection.loadCallPayload`, `prepareRequest`, `execute`, `createResponse` (Task 2).
- Produces: `AnthropicConnection extends OpenAiConnection`, `public static JSONObject toAnthropicRequest(JSONObject inOpenAi)`, `public static JSONObject toOpenAiResponse(JSONObject inAnthropic)`; overrides `chat(JSONObject)`.

- [ ] **Step 1: Verify the request shape against the live docs**

WebFetch `https://docs.anthropic.com/en/api/messages` and `https://docs.anthropic.com/en/docs/build-with-claude/structured-outputs`. Confirm: header `anthropic-version: 2023-06-01`, top-level `system`, `max_tokens` required, structured output as `output_config: {"format": {"type": "json_schema", "schema": {...}}}`, tool shape `{name, description, input_schema}`, tool choice `{"type": "tool", "name": ...}`, response `content[]` parts of type `text` / `tool_use`, `stop_reason` values including `refusal`, `usage.input_tokens/output_tokens`. If any key differs, use the documented key in Step 3 and in the test.

- [ ] **Step 2: Write the failing test**

```java
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
```

- [ ] **Step 3: Implement `AnthropicConnection`**

```java
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

	@Override
	protected LlmResponse chat(JSONObject inPayload)
	{
		JSONObject request = toAnthropicRequest(inPayload);
		log.info("Sent: " + request.toJSONString());

		HttpPost method = new HttpPost(getServerRoot() + "/messages");
		method.addHeader("x-api-key", getApiKey());
		method.addHeader("anthropic-version", ANTHROPIC_VERSION);
		method.setHeader("Content-Type", "application/json");
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
		out.put("max_tokens", maxTokens == null ? DEFAULT_MAX_TOKENS : maxTokens);
		for (String key : new String[] { "temperature", "top_p", "top_k", "stop_sequences", "thinking", "metadata" })
		{
			if (inOpenAi.get(key) != null)
			{
				out.put(key, inOpenAi.get(key));
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

			JSONObject toolChoice = (JSONObject) inOpenAi.get("tool_choice");
			if (toolChoice != null && toolChoice.get("function") != null)
			{
				JSONObject choice = new JSONObject();
				choice.put("type", "tool");
				choice.put("name", ((JSONObject) toolChoice.get("function")).get("name"));
				out.put("tool_choice", choice);
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
```

- [ ] **Step 4: Register the bean in `plugins/finder/html/src/plugin.xml`**

After the `llamaOpenAiConnection` bean:

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

- [ ] **Step 5: Run the test**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/compile.sh >/dev/null && java -cp "build:$(find plugins/system/lib plugins/finder/lib plugins/community/lib -name '*.jar' | tr '\n' ':')" junit.textui.TestRunner org.entermediadb.ai.llm.anthropic.AnthropicConnectionTest
```

Expected: `OK (6 tests)`.

- [ ] **Step 6: Commit (finder repo)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/finder && git add code/org/entermediadb/ai/llm/anthropic html/src/plugin.xml && git commit -m "LLM: Anthropic Messages API adapter behind the OpenAI-shaped templates

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: RoutedLlmConnection with failover, breaker, and call log

**Files:**
- Create: `plugins/finder/code/org/entermediadb/ai/llm/router/RoutedLlmConnection.java`
- Test: `plugins/finder/code/org/entermediadb/ai/llm/router/RoutedLlmConnectionTest.java`

**Interfaces:**
- Consumes: `BaseLlmConnection.setTimeoutOverride(Integer)` (Task 2); `LlmConnection`, `LlmResponse`, `Data`, `MediaArchive.getData(String, String)`, `MediaArchive.getSearcher(String)`.
- Produces: `public RoutedLlmConnection(MediaArchive inArchive, String inServerType, List<Data> inServers, DelegateFactory inFactory)`, nested `interface DelegateFactory { LlmConnection create(Data inServer); }`, `public static void closeBreaker(String inCatalogId, String inServerId)`, `public boolean isOlderThan(long inMillis)`, `public List<Data> getServers()`.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run it to see it fail**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/compile.sh 2>&1 | grep -E "error" | head -3
```

Expected: `cannot find symbol ... RoutedLlmConnection`.

- [ ] **Step 3: Implement `RoutedLlmConnection`**

```java
package org.entermediadb.ai.llm.router;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.conn.ConnectTimeoutException;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.llm.BaseLlmConnection;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.OpenEditException;
import org.openedit.data.Searcher;

/**
 * One LlmConnection that walks a chain of aiserver rows: the airoute row for the function if
 * present, else every enabled row of the type by ordering. Fails over on any error, timeout or
 * empty reply; a per-row circuit breaker skips rows that keep failing; every attempt is an
 * aicalllog row.
 */
public class RoutedLlmConnection implements LlmConnection
{
	private static Log log = LogFactory.getLog(RoutedLlmConnection.class);

	public interface DelegateFactory
	{
		LlmConnection create(Data inServer);
	}

	protected interface LlmCall
	{
		LlmResponse call(LlmConnection inConnection);
	}

	public static class Breaker
	{
		public int failures;
		public long openedAt;
		public boolean probing;
	}

	// ponytail: JVM-local breaker state keyed catalogid/aiserverid; a Tomcat restart resets it.
	protected static final Map<String, Breaker> BREAKERS = new ConcurrentHashMap<String, Breaker>();

	public static final int DEFAULT_BREAKER_FAILURES = 3;
	public static final int DEFAULT_BREAKER_MINUTES = 5;
	private static final Pattern HTTP_STATUS = Pattern.compile("\\bHTTP (\\d{3})\\b");

	protected MediaArchive fieldMediaArchive;
	protected String fieldServerType;
	protected List<Data> fieldServers;
	protected DelegateFactory fieldFactory;
	protected Map<String, LlmConnection> fieldDelegates = new HashMap<String, LlmConnection>();
	protected long fieldCreated = System.currentTimeMillis();

	public RoutedLlmConnection(MediaArchive inArchive, String inServerType, List<Data> inServers, DelegateFactory inFactory)
	{
		fieldMediaArchive = inArchive;
		fieldServerType = inServerType;
		fieldServers = inServers;
		fieldFactory = inFactory;
	}

	public List<Data> getServers()
	{
		return fieldServers;
	}

	public boolean isOlderThan(long inMillis)
	{
		return System.currentTimeMillis() - fieldCreated > inMillis;
	}

	public static void closeBreaker(String inCatalogId, String inServerId)
	{
		BREAKERS.remove(inCatalogId + "/" + inServerId);
	}

	public static Integer httpStatusOf(String inMessage)
	{
		if (inMessage == null)
		{
			return null;
		}
		Matcher m = HTTP_STATUS.matcher(inMessage);
		return m.find() ? Integer.valueOf(m.group(1)) : null;
	}

	// ---- chain resolution ----

	protected String catalogId()
	{
		return fieldMediaArchive == null ? "test" : fieldMediaArchive.getCatalogId();
	}

	protected Data route(String inFunction)
	{
		if (fieldMediaArchive == null || inFunction == null)
		{
			return null;
		}
		return fieldMediaArchive.getData("airoute", inFunction);
	}

	protected List<Data> resolveChain(String inFunction)
	{
		Data route = route(inFunction);
		if (route != null)
		{
			Collection ids = route.getValues("aiservers");
			if (ids != null && !ids.isEmpty())
			{
				List<Data> chain = new ArrayList<Data>();
				for (Object id : ids)
				{
					Data server = fieldMediaArchive.getData("aiserver", String.valueOf(id));
					if (server != null && !"false".equals(server.get("enabled")))
					{
						chain.add(server);
					}
				}
				if (!chain.isEmpty())
				{
					return chain;
				}
			}
		}
		return fieldServers;
	}

	protected Integer routeTimeout(String inFunction)
	{
		Data route = route(inFunction);
		if (route == null)
		{
			return null;
		}
		String value = route.get("timeoutseconds");
		if (value == null || value.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return Integer.valueOf(value.trim());
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
	}

	protected LlmConnection delegate(Data inServer)
	{
		LlmConnection connection = fieldDelegates.get(inServer.getId());
		if (connection == null)
		{
			connection = fieldFactory.create(inServer);
			fieldDelegates.put(inServer.getId(), connection);
		}
		return connection;
	}

	protected LlmConnection primary()
	{
		if (fieldServers.isEmpty())
		{
			throw new OpenEditException("No enabled aiserver of type " + fieldServerType);
		}
		return delegate(fieldServers.get(0));
	}

	protected int intValue(Data inServer, String inField, int inDefault)
	{
		String value = inServer.get(inField);
		if (value == null || value.trim().isEmpty())
		{
			return inDefault;
		}
		try
		{
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException ex)
		{
			return inDefault;
		}
	}

	protected boolean needsKey(Data inServer)
	{
		String bean = inServer.get("connectionbean");
		return "openaiConnection".equals(bean) || "anthropicConnection".equals(bean);
	}

	protected Breaker breaker(Data inServer)
	{
		String key = catalogId() + "/" + inServer.getId();
		Breaker b = BREAKERS.get(key);
		if (b == null)
		{
			b = new Breaker();
			BREAKERS.put(key, b);
		}
		return b;
	}

	// ---- the loop ----

	protected LlmResponse route(String inFunction, boolean inChatShaped, LlmCall inCall)
	{
		List<Data> chain = resolveChain(inFunction);
		if (chain.isEmpty())
		{
			throw new OpenEditException("No enabled aiserver of type " + fieldServerType + " for " + inFunction);
		}
		Integer timeoutOverride = routeTimeout(inFunction);
		List<String> tried = new ArrayList<String>();
		int attempt = 0;
		for (Data server : chain)
		{
			attempt++;
			Breaker b = breaker(server);
			int failuresAllowed = intValue(server, "breakerfailures", DEFAULT_BREAKER_FAILURES);
			long openMs = intValue(server, "breakerminutes", DEFAULT_BREAKER_MINUTES) * 60000L;
			boolean wasOpen;
			synchronized (b)
			{
				wasOpen = b.failures >= failuresAllowed;
				if (wasOpen)
				{
					boolean windowOver = System.currentTimeMillis() - b.openedAt >= openMs;
					if (!windowOver || b.probing)
					{
						tried.add(server.getId() + "(breaker open)");
						logAttempt(server, inFunction, "breakeropen", 0, attempt, null, null);
						continue;
					}
					b.probing = true; // exactly one half-open probe
				}
			}

			if (needsKey(server))
			{
				String key = server.get("serverapikey");
				if (key == null || key.trim().isEmpty())
				{
					synchronized (b)
					{
						b.probing = false;
					}
					tried.add(server.getId() + "(no key)");
					logAttempt(server, inFunction, "misconfigured", 0, attempt, "blank serverapikey", null);
					continue;
				}
			}

			LlmConnection connection = delegate(server);
			if (connection instanceof BaseLlmConnection)
			{
				((BaseLlmConnection) connection).setTimeoutOverride(timeoutOverride);
			}
			long start = System.currentTimeMillis();
			try
			{
				LlmResponse response = inCall.call(connection);
				String problem = inspect(response, inChatShaped);
				if (problem != null)
				{
					throw new OpenEditException(problem);
				}
				synchronized (b)
				{
					b.failures = 0;
					b.probing = false;
				}
				if (wasOpen)
				{
					logAttempt(server, inFunction, "breakerclosed", 0, attempt, null, null);
				}
				logAttempt(server, inFunction, "ok", System.currentTimeMillis() - start, attempt, null, response);
				return response;
			}
			catch (Throwable ex)
			{
				long ms = System.currentTimeMillis() - start;
				String status = isTimeout(ex) ? "timeout" : "error";
				synchronized (b)
				{
					b.failures++;
					b.probing = false;
					if (b.failures >= failuresAllowed)
					{
						b.openedAt = System.currentTimeMillis();
					}
				}
				String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
				tried.add(server.getId() + "(" + status + " " + ms + "ms)");
				log.error(inFunction + " failed on aiserver " + server.getId() + ": " + message);
				logAttempt(server, inFunction, status, ms, attempt, message, null);
			}
		}
		throw new OpenEditException("No AI server answered " + inFunction + "; tried " + String.join(", ", tried));
	}

	/** Null when the reply is usable; otherwise the reason it counts as a failed attempt. */
	protected String inspect(LlmResponse inResponse, boolean inChatShaped)
	{
		if (inResponse == null)
		{
			return "null response";
		}
		JSONObject raw = inResponse.getRawResponse();
		if (raw == null)
		{
			return inResponse.getRawCollection() == null ? "empty response" : null;
		}
		if (raw.get("error") != null)
		{
			return "provider error: " + String.valueOf(raw.get("error"));
		}
		if (!inChatShaped)
		{
			return null;
		}
		JSONArray choices = (JSONArray) raw.get("choices");
		if (choices == null || choices.isEmpty())
		{
			return "no choices in reply";
		}
		JSONObject message = (JSONObject) ((JSONObject) choices.get(0)).get("message");
		if (message == null)
		{
			return "no message in reply";
		}
		boolean toolCall = message.get("tool_calls") != null || message.get("function_call") != null;
		Object content = message.get("content");
		if (!toolCall && (content == null || String.valueOf(content).trim().isEmpty()))
		{
			return "empty message";
		}
		return null;
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

	/** One aicalllog row. Never throws; never contains a key. */
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
			row.setValue("functionname", inFunction == null ? fieldServerType : inFunction);
			row.setValue("aiserver", inServer.getId());
			row.setValue("modelname", inServer.get("modelname"));
			row.setValue("status", inStatus);
			row.setValue("ms", inMs);
			row.setValue("attempt", inAttempt);
			row.setValue("datecreated", new Date());
			if (inError != null)
			{
				row.setValue("errormessage", inError.length() > 500 ? inError.substring(0, 500) : inError);
				Integer http = httpStatusOf(inError);
				if (http != null)
				{
					row.setValue("httpstatus", http);
				}
			}
			if (inResponse != null && inResponse.getRawResponse() != null)
			{
				JSONObject usage = (JSONObject) inResponse.getRawResponse().get("usage");
				if (usage != null)
				{
					row.setValue("promptokens", usage.get("prompt_tokens"));
					row.setValue("completiontokens", usage.get("completion_tokens"));
				}
			}
			searcher.saveData(row, null);
		}
		catch (Throwable ex)
		{
			log.error("aicalllog write failed", ex);
		}
	}

	// ---- LlmConnection: routed calls ----

	public LlmResponse callStructure(final AgentContext inContext, final String inFunction)
	{
		return route(inFunction, true, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.callStructure(inContext, inFunction);
			}
		});
	}

	public LlmResponse callClassifyFunction(final AgentContext inContext, final String inFunction, final String inBase64Image)
	{
		return route(inFunction, true, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.callClassifyFunction(inContext, inFunction, inBase64Image);
			}
		});
	}

	public LlmResponse callClassifyFunction(final AgentContext inContext, final String inFunction, final String inBase64Image, final String inText)
	{
		return route(inFunction, true, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.callClassifyFunction(inContext, inFunction, inBase64Image, inText);
			}
		});
	}

	public LlmResponse callToolsFunction(final AgentContext inContext, final String inFunction)
	{
		return route(inFunction, true, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.callToolsFunction(inContext, inFunction);
			}
		});
	}

	public LlmResponse callCreateFunction(final AgentContext inContext, final String inFunction)
	{
		return route(inFunction, true, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.callCreateFunction(inContext, inFunction);
			}
		});
	}

	public LlmResponse callSmartCreatorAiAction(final AgentContext inContext, final String inActionName)
	{
		return route(inActionName, true, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.callSmartCreatorAiAction(inContext, inActionName);
			}
		});
	}

	public LlmResponse runPageAsInput(final AgentContext inContext, final String inTemplate)
	{
		return route(inTemplate, true, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.runPageAsInput(inContext, inTemplate);
			}
		});
	}

	public LlmResponse callOCRFunction(final AgentContext inContext, final String inBase64Image, final String inFunction)
	{
		return route(inFunction, false, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.callOCRFunction(inContext, inBase64Image, inFunction);
			}
		});
	}

	public LlmResponse createImage(final String inPrompt)
	{
		return route("createimage", false, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.createImage(inPrompt);
			}
		});
	}

	public LlmResponse createImage(final String inPrompt, final int inCount, final String inSize)
	{
		return route("createimage", false, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.createImage(inPrompt, inCount, inSize);
			}
		});
	}

	public LlmResponse callJson(final String inPath, final Map inPayload)
	{
		return route(inPath, false, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.callJson(inPath, inPayload);
			}
		});
	}

	public LlmResponse callJson(final String inPath, final JSONObject inPayload)
	{
		return route(inPath, false, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.callJson(inPath, inPayload);
			}
		});
	}

	public LlmResponse callJson(final String inPath, final Map<String, String> inHeaders, final Map inMap)
	{
		return route(inPath, false, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.callJson(inPath, inHeaders, inMap);
			}
		});
	}

	public LlmResponse callJson(final String inPath, final Map<String, String> inHeaders, final JSONObject inPayload)
	{
		return route(inPath, false, new LlmCall()
		{
			public LlmResponse call(LlmConnection c)
			{
				return c.callJson(inPath, inHeaders, inPayload);
			}
		});
	}

	// ---- LlmConnection: local / read-only, answered by the first row ----

	public String loadInputFromTemplate(AgentContext inContext, String inTemplate)
	{
		return primary().loadInputFromTemplate(inContext, inTemplate);
	}

	public LlmResponse renderLocalAction(AgentContext inContext, String inTemplate)
	{
		return primary().renderLocalAction(inContext, inTemplate);
	}

	public String getApiKey()
	{
		return primary().getApiKey();
	}

	public String getServerRoot()
	{
		return primary().getServerRoot();
	}

	public String getLlmProtocol()
	{
		return primary().getLlmProtocol();
	}

	public String getModelName()
	{
		return primary().getModelName();
	}

	public Boolean isReady()
	{
		return primary().isReady();
	}

	public Data getAiServerData()
	{
		return primary().getAiServerData();
	}

	public void setAiServerData(Data inServer)
	{
		log.info("setAiServerData ignored on routed connection; edit aiserver rows instead");
	}

	public LlmResponse createResponse()
	{
		return primary().createResponse();
	}
}
```

Note: in the test the `logAttempt` override takes seven parameters in this order: `(Data, String, String, long, int, String, LlmResponse)`. Keep the signature identical.

- [ ] **Step 4: Run the test**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/compile.sh >/dev/null && java -cp "build:$(find plugins/system/lib plugins/finder/lib plugins/community/lib -name '*.jar' | tr '\n' ':')" junit.textui.TestRunner org.entermediadb.ai.llm.router.RoutedLlmConnectionTest
```

Expected: `OK (9 tests)`.

- [ ] **Step 5: Commit (finder repo)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/finder && git add code/org/entermediadb/ai/llm/router/RoutedLlmConnection.java code/org/entermediadb/ai/llm/router/RoutedLlmConnectionTest.java && git commit -m "LLM: RoutedLlmConnection with failover chain, circuit breaker and aicalllog rows

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Wire the router into MediaArchive and fix the health poll

**Files:**
- Modify: `plugins/finder/code/org/entermediadb/asset/MediaArchive.java:3408-3435`
- Modify: `plugins/finder/code/org/entermediadb/ai/assistant/AssistantManager.java:781-838` (`monitorAiServers`)

**Interfaces:**
- Consumes: `RoutedLlmConnection` constructor, `isOlderThan`, `closeBreaker` (Task 4).
- Produces: unchanged `public LlmConnection getLlmConnection(String inServerType)`; all 74 callers keep working.

- [ ] **Step 1: Replace `getLlmConnection`**

Add imports `java.util.ArrayList`, `java.util.List`, `org.entermediadb.ai.llm.router.RoutedLlmConnection` (check which of the `java.util` ones already exist). Replace the method body:

```java
	public LlmConnection getLlmConnection(String inServerType)
	{
		String cacheName = "llmconnection";
		LlmConnection connection = (LlmConnection) getCacheManager().get(cacheName, inServerType);
		// ponytail: nothing clears this cache when an admin edits aiserver/airoute rows; rebuild every 60 s.
		if (connection instanceof RoutedLlmConnection && ((RoutedLlmConnection) connection).isOlderThan(60 * 1000))
		{
			connection = null;
		}
		if (connection == null)
		{
			List<Data> servers = new ArrayList<Data>();
			for (Object hit : query("aiserver").exact("aiservertype", inServerType).sort("ordering").search())
			{
				Data server = (Data) hit;
				if (!"false".equals(server.get("enabled")))
				{
					servers.add(server);
				}
			}
			if (servers.isEmpty())
			{
				Data localhost = getCachedData("aiserver", "localhost");
				if (localhost == null)
				{
					throw new OpenEditException("No enabled aiserver of type " + inServerType);
				}
				servers.add(localhost);
			}
			connection = new RoutedLlmConnection(this, inServerType, servers, new RoutedLlmConnection.DelegateFactory()
			{
				public LlmConnection create(Data inServer)
				{
					LlmConnection real = (LlmConnection) getModuleManager().getBean(getCatalogId(), inServer.get("connectionbean"), false);
					real.setAiServerData(inServer);
					return real;
				}
			});
			getCacheManager().put(cacheName, inServerType, connection);
			List<String> ids = new ArrayList<String>();
			for (Data server : servers)
			{
				ids.add(server.getId());
			}
			log.info(inServerType + " routed llmconnection chain: " + ids);
		}
		return connection;
	}
```

- [ ] **Step 2: Fix `monitorAiServers`**

Replace `connection.addSharedHeader("Authorization", "Bearer " + server);` with `connection.addSharedHeader("Authorization", "Bearer " + key);`.

Replace the `catch (Exception ex)` body's `speeds.put(serverroot, Integer.MAX_VALUE);` with `speeds.put(serverroot, -1);`.

Inside the `if ("ok".equals(ok))` block, after `speeds.put(serverroot, diff);` add:

```java
							RoutedLlmConnection.closeBreaker(getMediaArchive().getCatalogId(), server.getId());
```

In the save loop replace `server.setValue("ordering", speed);` with `server.setValue("healthms", speed);`. Add the import `org.entermediadb.ai.llm.router.RoutedLlmConnection`.

- [ ] **Step 3: Compile and run all three test classes**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/compile.sh >/dev/null && CP="build:$(find plugins/system/lib plugins/finder/lib plugins/community/lib -name '*.jar' | tr '\n' ':')" && for t in org.entermediadb.ai.llm.openai.OpenAiConnectionPrepareTest org.entermediadb.ai.llm.anthropic.AnthropicConnectionTest org.entermediadb.ai.llm.router.RoutedLlmConnectionTest; do java -cp "$CP" junit.textui.TestRunner $t | tail -1; done
```

Expected: three `OK` lines.

- [ ] **Step 4: Restart the local Tomcat and check the tutor still answers through llamat**

Tell peers first (shared Tomcat). Then:

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/restart.sh
```

Send one tutor message from the simulator or with `plugins/testu/tools/check_tutor.sh` as a local learner (`EME_USER=colab1@minsur.test`), then:

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/logs.sh 2>/dev/null | grep -E "routed llmconnection chain|aicalllog write failed|No AI server answered" | tail -5
```

Expected: a `thinking routed llmconnection chain: [llamat]` line and no `aicalllog write failed` line. In the admin UI, open the `aicalllog` table for the catalog and confirm one `ok` row with `functionname=chat_tutor_usercomment`. If the new `aiserver` fields do not show on the edit form, reset the table's mappings from the admin data manager and reload.

- [ ] **Step 5: Commit (finder repo)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/finder && git add code/org/entermediadb/asset/MediaArchive.java code/org/entermediadb/ai/assistant/AssistantManager.java && git commit -m "LLM: getLlmConnection returns the routed chain; health poll writes healthms, closes breakers, sends the real key

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Daily aicalllog cleanup

**Files:**
- Create: `plugins/finder/code/org/entermediadb/ai/llm/router/AiCallLogCleaner.java`
- Create: `plugins/catalog/html/events/llm/cleanaicalllog.xconf`
- Create: `plugins/catalog/html/events/scripts/llm/cleanaicalllog.groovy`

**Interfaces:**
- Produces: `public static int AiCallLogCleaner.deleteOlderThanDays(MediaArchive inArchive, int inDays)` returning the number of rows deleted.

- [ ] **Step 1: Implement the cleaner**

```java
package org.entermediadb.ai.llm.router;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.entermediadb.asset.MediaArchive;
import org.openedit.Data;
import org.openedit.hittracker.HitTracker;

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

- [ ] **Step 2: Add the event**

`plugins/catalog/html/events/llm/cleanaicalllog.xconf`:

```xml
<page>
	<property name="eventname">Clean AI call log</property>
	<property name="eventdescription">Deletes aicalllog rows older than 30 days</property>

	<property name="enabled">true</property>
	<property name="period">24h</property>
	<property name="startingfrommidnight">4h</property>

	<path-action name="Script.run">
		<script>/${catalogid}/events/scripts/llm/cleanaicalllog.groovy</script>
	</path-action>
</page>
```

`plugins/catalog/html/events/scripts/llm/cleanaicalllog.groovy`:

```groovy
import org.entermediadb.ai.llm.router.AiCallLogCleaner
import org.entermediadb.asset.MediaArchive

MediaArchive archive = context.getPageValue("mediaarchive");
int deleted = AiCallLogCleaner.deleteOlderThanDays(archive, 30);
log.info("aicalllog cleanup deleted " + deleted + " rows");
```

- [ ] **Step 3: Compile, then run the event once from the admin events page**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/compile.sh 2>&1 | grep -c error
```

Expected: `0`. Restart Tomcat (tell peers), open the catalog's events list in the admin UI, run "Clean AI call log" and check the log line `aicalllog cleanup deleted 0 rows`.

- [ ] **Step 4: Commit (finder and catalog repos)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/finder && git add code/org/entermediadb/ai/llm/router/AiCallLogCleaner.java && git commit -m "LLM: aicalllog cleaner

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && cd ../catalog && git add html/events/llm/cleanaicalllog.xconf html/events/scripts/llm/cleanaicalllog.groovy && git commit -m "Daily aicalllog cleanup event

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Admin-only eval endpoint

**Files:**
- Create: `plugins/finder/code/org/entermediadb/ai/llm/router/LlmEvalModule.java`
- Modify: `plugins/finder/html/src/plugin.xml` (bean `LlmEvalModule`)
- Create: `plugins/mediadb/html/services/llm/evalcall.xconf`
- Create: `plugins/mediadb/html/services/llm/evalcall.json`

**Interfaces:**
- Consumes: `LlmConnection.callStructure`, `loadInputFromTemplate`, `getLlmProtocol`; `MediaArchive.getData`, `getMediaDbId`.
- Produces: `POST /site/mediadb/services/llm/evalcall.json` with form fields `function`, `aiserver`, `input` (JSON object of template variables), optional `dryrun=true`. Reply JSON: `{ok, aiserver, function, ms, message, payload, usage, error, rendered}`.

- [ ] **Step 1: Implement the module**

```java
package org.entermediadb.ai.llm.router;

import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.llm.BaseAgentContext;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.asset.modules.BaseMediaModule;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.users.User;
import org.openedit.util.JSONParser;

/** Calls exactly one aiserver row for one function, no failover, so the eval harness can compare providers. */
public class LlmEvalModule extends BaseMediaModule
{
	public void evalCall(WebPageRequest inReq)
	{
		User user = inReq.getUser();
		if (user == null || !user.isInGroup("administrators"))
		{
			fail(inReq, 403, "administrators only");
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		String function = inReq.getRequestParameter("function");
		String serverid = inReq.getRequestParameter("aiserver");
		if (function == null || serverid == null)
		{
			fail(inReq, 400, "function and aiserver are required");
			return;
		}
		Data server = archive.getData("aiserver", serverid);
		if (server == null)
		{
			fail(inReq, 400, "unknown aiserver " + serverid);
			return;
		}

		AgentContext context = new BaseAgentContext();
		context.setCatalogId(archive.getCatalogId());
		context.setModuleManager(archive.getModuleManager());
		context.addContext("mediaarchive", archive);
		String input = inReq.getRequestParameter("input");
		if (input != null && !input.trim().isEmpty())
		{
			JSONObject values = new JSONParser().parse(input);
			for (Object key : values.keySet())
			{
				context.putContextValue((String) key, values.get(key));
			}
		}

		LlmConnection connection = (LlmConnection) archive.getModuleManager().getBean(archive.getCatalogId(), server.get("connectionbean"), false);
		connection.setAiServerData(server);

		JSONObject out = new JSONObject();
		out.put("aiserver", serverid);
		out.put("function", function);
		out.put("model", server.get("modelname"));

		if ("true".equals(inReq.getRequestParameter("dryrun")))
		{
			context.put("model", server.get("modelname"));
			String path = "/" + archive.getMediaDbId() + "/ai/" + connection.getLlmProtocol() + "/calls/" + function + ".json";
			out.put("ok", Boolean.TRUE);
			out.put("rendered", connection.loadInputFromTemplate(context, path));
			reply(inReq, out);
			return;
		}

		long start = System.currentTimeMillis();
		try
		{
			LlmResponse response = connection.callStructure(context, function);
			out.put("ok", Boolean.TRUE);
			out.put("message", response.getMessage());
			out.put("payload", response.getResponsePayload());
			if (response.getRawResponse() != null)
			{
				out.put("usage", response.getRawResponse().get("usage"));
			}
		}
		catch (Throwable ex)
		{
			out.put("ok", Boolean.FALSE);
			out.put("error", String.valueOf(ex.getMessage()));
		}
		out.put("ms", System.currentTimeMillis() - start);
		reply(inReq, out);
	}

	protected void reply(WebPageRequest inReq, JSONObject inJson)
	{
		inReq.putPageValue("json", inJson.toJSONString());
	}

	protected void fail(WebPageRequest inReq, int inStatus, String inMessage)
	{
		if (inReq.getResponse() != null)
		{
			inReq.getResponse().setStatus(inStatus);
		}
		JSONObject err = new JSONObject();
		err.put("ok", Boolean.FALSE);
		err.put("error", inMessage);
		reply(inReq, err);
		inReq.setCancelActions(true);
	}
}
```

- [ ] **Step 2: Register the bean** in `plugins/finder/html/src/plugin.xml`, next to `assistantManager`:

```xml
	<bean id="LlmEvalModule" class="org.entermediadb.ai.llm.router.LlmEvalModule" scope="prototype">
		<property name="moduleManager">
			<ref bean="moduleManager" />
		</property>
	</bean>
```

- [ ] **Step 3: Add the endpoint in the mediadb plugin**

`plugins/mediadb/html/services/llm/evalcall.xconf`:

```xml
<page>
	<path-action name="LlmEvalModule.evalCall"/>
	<permission name="view">
		<blank/>
	</permission>
</page>
```

`plugins/mediadb/html/services/llm/evalcall.json`:

```velocity
$json
```

- [ ] **Step 4: Compile, restart, and call it dry**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur && bin/compile.sh 2>&1 | grep -c error
```

Expected `0`. Restart Tomcat (tell peers). Then:

```bash
B=http://localhost:8080/site/mediadb; J=$(mktemp); curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d '{"id":"admin","password":"admin"}' && curl -s -b "$J" -X POST "$B/services/llm/evalcall.json" -d function=chat_tutor_usercomment -d aiserver=llamat -d dryrun=true --data-urlencode 'input={"learnerprompt":"¿Qué es la debida diligencia?","chathistory":[],"referenceexcerpts":""}' | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["ok"], d.get("error"), d.get("rendered","")[:120])'
```

Expected: `True None {` followed by the rendered template start. Then the same command without `dryrun=true` returns `ok True` and a Spanish `message` from llamat. Without the cookie (`-b "$J"` removed) the endpoint returns the noaccess JSON, not a reply.

- [ ] **Step 5: Commit (finder and mediadb repos)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/finder && git add code/org/entermediadb/ai/llm/router/LlmEvalModule.java html/src/plugin.xml && git commit -m "LLM: admin-only evalcall module calling one aiserver with no failover

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && cd ../mediadb && git add html/services/llm/evalcall.xconf html/services/llm/evalcall.json && git commit -m "services/llm/evalcall.json endpoint for the LLM eval harness

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Eval harness and TestU golden set

**Files:**
- Create: `plugins/finder/tools/llmeval/run.sh`
- Create: `plugins/finder/tools/llmeval/judge.sh`
- Create: `plugins/finder/tools/llmeval/report.sh`
- Create: `plugins/finder/tools/llmeval/prices.json`
- Create: `plugins/mediadb/html/ai/default/calls/llm_eval_judge.json`
- Create: `plugins/testu/tools/llmeval/golden.jsonl`

**Interfaces:**
- Consumes: `services/llm/evalcall.json` (Task 7).
- Produces: `results/<date>-<aiserver>.jsonl` rows `{id, function, aiserver, ok, ms, message, usage, error, expected}`; `*.judged.jsonl` adds `checks` (list of failed check names), `score` (1-5), `reason`.

- [ ] **Step 1: Golden set (seed; grows by hand)**

`plugins/testu/tools/llmeval/golden.jsonl`, one prompt per line. The `input` keys are the template variables the skill fills; `expected` holds deterministic checks.

```jsonl
{"id":"dd-def","function":"chat_tutor_usercomment","input":{"learnerprompt":"¿Qué es la debida diligencia en derechos humanos?","chathistory":[{"content":"Tema: Empresas y derechos humanos. Subtema: Debida diligencia."}],"referenceexcerpts":"[Plan Nacional de Acción sobre Empresas y Derechos Humanos 2021-2025, p. 12]\nLa debida diligencia en derechos humanos es el proceso continuo por el cual una empresa identifica, previene, mitiga y rinde cuentas de los impactos adversos de su actividad.","mode":"learn"},"expected":{"must_cite":["Plan Nacional de Acción sobre Empresas y Derechos Humanos 2021-2025, p. 12"],"must_not_say":["No lo encuentro","provided context","the provided","I cannot"],"max_words":70,"followups_min":1,"followups_max":2}}
{"id":"not-found","function":"chat_tutor_usercomment","input":{"learnerprompt":"¿Cuál es la capital de la Luna?","chathistory":[{"content":"Tema: Empresas y derechos humanos."}],"referenceexcerpts":"[Plan Nacional de Acción sobre Empresas y Derechos Humanos 2021-2025, p. 12]\nLa debida diligencia en derechos humanos es el proceso continuo por el cual una empresa identifica, previene, mitiga y rinde cuentas de los impactos adversos de su actividad.","mode":"learn"},"expected":{"must_start_with":"No lo encuentro en las fuentes de este tema.","must_not_say":["capital"],"max_words":40,"followups_min":1,"followups_max":1}}
{"id":"hint-no-spoiler","function":"chat_tutor_usercomment","input":{"learnerprompt":"Dame una pista para esta pregunta sin decirme la respuesta.","chathistory":[{"content":"Pregunta en juego: ¿Cuál de estas acciones forma parte de la debida diligencia? Opciones: A) Ignorar quejas de la comunidad. B) Identificar y prevenir impactos adversos. C) Publicar solo resultados financieros. Opción correcta: B. Justificación: la debida diligencia identifica, previene y mitiga impactos."}],"referenceexcerpts":"","mode":"learn"},"expected":{"must_not_say":["Identificar y prevenir impactos adversos","opción B","la B","No lo encuentro"],"max_words":70,"followups_min":1,"followups_max":2}}
```

- [ ] **Step 2: Judge template** `plugins/mediadb/html/ai/default/calls/llm_eval_judge.json`

```velocity
{
	"model": "${model}",
	"messages": [
		{
			"role": "system",
			"content": #jesc("You grade one reply from a corporate training tutor. Score 1-5 on three axes and give one short reason.
correctness: is every factual claim right and consistent with the reference excerpts?
groundedness: does the reply only use the excerpts or the question in play, with the citations copied exactly, or explicitly say it cannot find it?
tone: calm senior-colleague register in Spanish, tú form, no shaming, no system-style refusals, within length rules.
Return only the JSON object.")
		},
		{
			"role": "user",
			"content": #jesc("LEARNER QUESTION:
${question}

REFERENCE EXCERPTS:
${reference}

TUTOR REPLY:
${reply}")
		}
	],
	"temperature": 0,
	"max_tokens": 300,
	"response_format": {
		"type": "json_schema",
		"json_schema": {
			"name": "judge",
			"strict": true,
			"schema": {
				"type": "object",
				"properties": {
					"correctness": { "type": "integer" },
					"groundedness": { "type": "integer" },
					"tone": { "type": "integer" },
					"reason": { "type": "string" }
				},
				"required": ["correctness", "groundedness", "tone", "reason"],
				"additionalProperties": false
			}
		}
	}
}
```

- [ ] **Step 3: `run.sh`**

```sh
#!/bin/sh
# Runs every golden prompt against each named aiserver through services/llm/evalcall.json (no failover).
# Usage: tools/llmeval/run.sh <golden.jsonl> <aiserver-id>...
#   EME_BASE (default http://localhost:8080/site/mediadb), EME_ADMIN_PW (default admin), DRYRUN=1 renders only.
# Output: <golden dir>/results/<YYYYmmdd-HHMM>-<aiserver>.jsonl
set -eu
G=$1; shift
[ $# -ge 1 ] || { echo "usage: run.sh golden.jsonl aiserver..."; exit 2; }
B=${EME_BASE:-http://localhost:8080/site/mediadb}
J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d "{\"id\":\"admin\",\"password\":\"${EME_ADMIN_PW:-admin}\"}"
OUTDIR=$(dirname "$G")/results; mkdir -p "$OUTDIR"; STAMP=$(date +%Y%m%d-%H%M)
for S in "$@"; do
  OUT="$OUTDIR/$STAMP-$S.jsonl"
  python3 - "$G" "$S" "$B" "$J" "${DRYRUN:-0}" > "$OUT" <<'PY'
import json, sys, urllib.request, urllib.parse
golden, server, base, jar, dry = sys.argv[1:6]
cookie = '; '.join(f"{p[5]}={p[6]}" for p in (l.split('\t') for l in open(jar)) if len(p) == 7)
for line in open(golden):
    line = line.strip()
    if not line: continue
    g = json.loads(line)
    form = {'function': g['function'], 'aiserver': server, 'input': json.dumps(g['input'])}
    if dry == '1': form['dryrun'] = 'true'
    req = urllib.request.Request(f"{base}/services/llm/evalcall.json", data=urllib.parse.urlencode(form).encode(), headers={'Cookie': cookie})
    try:
        d = json.load(urllib.request.urlopen(req, timeout=600))
    except Exception as ex:
        d = {'ok': False, 'error': str(ex), 'ms': None}
    d.update(id=g['id'], expected=g.get('expected', {}), question=g['input'].get('learnerprompt', ''), reference=g['input'].get('referenceexcerpts', ''))
    print(json.dumps(d, ensure_ascii=False)); sys.stdout.flush()
PY
  echo "wrote $OUT ($(wc -l < "$OUT") rows)"
done
```

- [ ] **Step 4: `judge.sh`**

```sh
#!/bin/sh
# Deterministic checks, then an LLM judge (function llm_eval_judge on JUDGE aiserver, default anthropic).
# Usage: tools/llmeval/judge.sh results/<run>.jsonl   -> writes results/<run>.judged.jsonl
set -eu
R=$1; JUDGE=${JUDGE:-anthropic}; B=${EME_BASE:-http://localhost:8080/site/mediadb}
J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d "{\"id\":\"admin\",\"password\":\"${EME_ADMIN_PW:-admin}\"}"
OUT="${R%.jsonl}.judged.jsonl"
python3 - "$R" "$JUDGE" "$B" "$J" > "$OUT" <<'PY'
import json, re, sys, urllib.request, urllib.parse
path, judge, base, jar = sys.argv[1:5]
cookie = '; '.join(f"{p[5]}={p[6]}" for p in (l.split('\t') for l in open(jar)) if len(p) == 7)
def checks(reply, exp):
    failed = []
    body = re.sub(r'^[ \t]*>>.*$', '', reply, flags=re.M)
    follow = re.findall(r'^[ \t]*>>', reply, flags=re.M)
    words = len(re.sub(r'\[[^\]]*\]', '', body).split())
    for c in exp.get('must_cite', []):
        if c not in reply: failed.append('must_cite:' + c)
    for s in exp.get('must_not_say', []):
        if s.lower() in reply.lower(): failed.append('must_not_say:' + s)
    if 'must_start_with' in exp and not body.strip().startswith(exp['must_start_with']): failed.append('must_start_with')
    if 'max_words' in exp and words > exp['max_words']: failed.append(f'max_words:{words}')
    if 'followups_min' in exp and len(follow) < exp['followups_min']: failed.append('followups_min')
    if 'followups_max' in exp and len(follow) > exp['followups_max']: failed.append('followups_max')
    return failed
for line in open(path):
    d = json.loads(line)
    reply = (d.get('payload') or {}).get('message') or d.get('message') or ''
    d['reply'] = reply
    d['checks'] = checks(reply, d.get('expected', {})) if d.get('ok') else ['no_reply']
    if d.get('ok'):
        form = {'function': 'llm_eval_judge', 'aiserver': judge, 'input': json.dumps({'question': d['question'], 'reference': d['reference'], 'reply': reply})}
        req = urllib.request.Request(f"{base}/services/llm/evalcall.json", data=urllib.parse.urlencode(form).encode(), headers={'Cookie': cookie})
        try:
            j = json.load(urllib.request.urlopen(req, timeout=300)).get('payload') or {}
            d['score'] = round((j['correctness'] + j['groundedness'] + j['tone']) / 3, 2); d['reason'] = j['reason']
        except Exception as ex:
            d['score'] = None; d['reason'] = 'judge failed: ' + str(ex)
    print(json.dumps(d, ensure_ascii=False))
PY
echo "wrote $OUT"
```

- [ ] **Step 5: `report.sh` and `prices.json`**

`prices.json` (USD per million tokens; update by hand):

```json
{
  "llamat": {"input": 0, "output": 0},
  "groq": {"input": 0.59, "output": 0.79},
  "openrouter": {"input": 0.60, "output": 3.00},
  "together": {"input": 0.88, "output": 0.88},
  "anthropic": {"input": 15.0, "output": 75.0}
}
```

`report.sh`:

```sh
#!/bin/sh
# Markdown table over one or more *.judged.jsonl files.
# Usage: tools/llmeval/report.sh results/*.judged.jsonl
set -eu
python3 - "$(dirname "$0")/prices.json" "$@" <<'PY'
import json, statistics, sys
prices = json.load(open(sys.argv[1]))
print('| run | server | n | pass | median ms | p95 ms | judge | cost USD |'); print('|---|---|---|---|---|---|---|---|')
for path in sys.argv[2:]:
    rows = [json.loads(l) for l in open(path) if l.strip()]
    if not rows: continue
    server = rows[0].get('aiserver', '?')
    ok = [r for r in rows if r.get('ok') and not r['checks']]
    ms = sorted(r['ms'] for r in rows if r.get('ms') is not None)
    p95 = ms[min(len(ms) - 1, int(len(ms) * 0.95))] if ms else 0
    scores = [r['score'] for r in rows if r.get('score') is not None]
    p = prices.get(server, {'input': 0, 'output': 0})
    cost = sum(((r.get('usage') or {}).get('prompt_tokens') or 0) * p['input'] + ((r.get('usage') or {}).get('completion_tokens') or 0) * p['output'] for r in rows) / 1e6
    print(f"| {path.split('/')[-1]} | {server} | {len(rows)} | {len(ok)}/{len(rows)} | {int(statistics.median(ms)) if ms else '-'} | {int(p95)} | {round(statistics.mean(scores), 2) if scores else '-'} | {cost:.4f} |")
PY
```

- [ ] **Step 6: Make executable, dry-run against llamat, run for real**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins && chmod +x finder/tools/llmeval/*.sh && DRYRUN=1 finder/tools/llmeval/run.sh testu/tools/llmeval/golden.jsonl llamat && tail -1 testu/tools/llmeval/results/*-llamat.jsonl | python3 -c 'import sys,json;d=json.loads(sys.stdin.read());print(d["ok"], d["id"], "rendered" in d)'
```

Expected: `True hint-no-spoiler True`. Then without `DRYRUN`:

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins && finder/tools/llmeval/run.sh testu/tools/llmeval/golden.jsonl llamat && R=$(ls -t testu/tools/llmeval/results/*-llamat.jsonl | head -1) && JUDGE=llamat finder/tools/llmeval/judge.sh "$R" && finder/tools/llmeval/report.sh "${R%.jsonl}.judged.jsonl"
```

Expected: a three-row run, a judged file, and a one-line Markdown table. (Judge on llamat here only proves the pipe; real judging uses the Anthropic row from Task 9.)

- [ ] **Step 7: Ignore results and commit (finder, mediadb, testu)**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins/testu && printf 'results/\n' > tools/llmeval/.gitignore && git add tools/llmeval/golden.jsonl tools/llmeval/.gitignore && git commit -m "TestU LLM eval golden set (seed)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && cd ../finder && git add tools/llmeval && git commit -m "LLM eval harness: run, judge, report

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>" && cd ../mediadb && git add html/ai/default/calls/llm_eval_judge.json && git commit -m "llm_eval_judge call template

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: Provider rows on dev, first comparison, route for the tutor

**Files:** none in git. Admin UI data on the local catalog only. Needs the Groq and Anthropic keys from the user (never pasted into files or chat logs beyond the admin form).

- [ ] **Step 1: Fill the rows**

In the admin UI, edit `aiserver` rows `groq` and `anthropic`: paste the key into Server Api Key, set Enabled = true. Leave `ordering` 20 and 50 so llamat (10) stays first in the type chain.

- [ ] **Step 2: Run the comparison and the Anthropic judge**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins && finder/tools/llmeval/run.sh testu/tools/llmeval/golden.jsonl llamat groq anthropic && for R in $(ls -t testu/tools/llmeval/results/*.jsonl | grep -v judged | head -3); do finder/tools/llmeval/judge.sh "$R"; done && finder/tools/llmeval/report.sh testu/tools/llmeval/results/*.judged.jsonl
```

Expected: one table row per server with pass counts, latency and judge score. Paste the table into the chat for the ordering decision.

- [ ] **Step 3: Create the `airoute` row for the tutor**

Admin UI → `airoute` → new row: id `chat_tutor_usercomment`, name "Tutor follow-up", AI servers in the order the table justified (e.g. groq, llamat, anthropic), timeout 30. Within 60 s the router picks it up. Send one tutor message and confirm in `aicalllog` that the first attempt hit the first server in the route.

- [ ] **Step 4: Prove failover once**

Set `groq`'s Server Root to `https://api.groq.com/openai-broken` for one call: the `aicalllog` shows `groq error` then `llamat ok`, and the learner still got an answer. Restore the root.

---

### Task 10: Deploy path and upstream branches

- [ ] **Step 1: Decide and record the production origin**

Production `bin/eme.sh update` pulls plugins from entermedia-community. Until the upstream PR merges, point the Minsur server's `plugins/finder`, `plugins/catalog` and `plugins/mediadb` origins at our forks (`https://github.com/DSANJORGE/eme-plugin-*.git`) or the routing never runs there. This is a server change Chris or Cristobal runs; send them the three `git remote set-url origin ...` lines and note it in the pilot memory.

- [ ] **Step 2: Branches for the upstream PRs**

```bash
cd /Users/DSANJORGE/Code/EMEGenAILabs/eme-server-minsur/plugins && for p in finder catalog mediadb; do (cd $p && git checkout -b llm-routing && git push -u origin llm-routing && git checkout main); done
```

PR bodies (one per repo) describe: `aiserver` new fields, `airoute` and `aicalllog` tables, `RoutedLlmConnection`, `AnthropicConnection`, `extraparams`, health poll change, eval endpoint and harness, tests. Attach the Task 9 report table. State that example rows ship disabled with blank keys. End each body with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. The upstream `BaseLlmConnection` may already carry a 1200 s `LLM_SOCKET_TIMEOUT` constant (their commit b4db43f95); resolve by keeping `MAX_TIMEOUT_SECONDS = 1200` as the ceiling and the per-row `execute()` as the value.

---

## Self-review notes

- Spec §1 → Task 1 (fields, lists, example rows). §2 → Tasks 4, 5. §3 → Tasks 2, 3. §4 → Tasks 7, 8, 9 (observability is the `aicalllog` table in the generic admin data manager; no bespoke view). §5 → Tasks 2, 3, 4 (error text, timeouts, breaker, misconfigured, log try/catch, no keys in messages). §6 → tests in Tasks 2–4, rollout in Tasks 5, 9, 10.
- Names used across tasks: `setTimeoutOverride`, `getTimeoutSeconds`, `mergeExtraParams`, `execute`, `prepareRequest`, `loadCallPayload`, `chat`, `toAnthropicRequest`, `toOpenAiResponse`, `isRefusal`, `RoutedLlmConnection.DelegateFactory`, `closeBreaker`, `isOlderThan`, `httpStatusOf`, `logAttempt(Data, String, String, long, int, String, LlmResponse)`, `AiCallLogCleaner.deleteOlderThanDays`, `LlmEvalModule.evalCall`.
