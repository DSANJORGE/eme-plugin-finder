# LLM provider routing and failover — design

Date: 2026-09-15. Repos: `plugins/finder` (code, tests, eval harness), `plugins/catalog` (fields, lists, events), `plugins/mediadb` (eval endpoint, judge template). Intended for an upstream PR to EnterMedia. Amended 2026-09-15 while planning: see "Deviations" at the end.

## Goal

Let each catalog choose which LLM server answers each AI function, in order, with automatic failover on error or timeout, and measure quality and latency per server. Generic eMe infrastructure: nothing TestU-specific in this layer. API keys stay in `aiserver` rows and are never committed or shared.

## Non-goals

Streaming, hedged parallel calls, per-user routing, fine-tuning workflow, the remote embed key fix, session-tutor context enrichment, generic-tutor chat history UI. Each is a separate spec.

## 1. Data model

### `aiserver` (existing table, new fields)

| Field | Type | Default | Meaning |
|---|---|---|---|
| `disabled` | boolean | false | Router skips rows marked disabled |
| `timeoutseconds` | number | 30 | Socket timeout per attempt |
| `breakerfailures` | number | 3 | Consecutive failures that open the breaker |
| `breakerminutes` | number | 5 | Open window before one probe |
| `healthms` | number | | Written by the health poll; `ordering` is no longer overwritten |
| `extraparams` | text (JSON) | | Merged into every request to this row, provider-specific options |

`aifunctions` (multi-value, never read) is left as is. `llmtype` list gains `anthropic`; bean `anthropicConnection`.

`baseaiserver.xml` ships example rows `groq`, `openrouter`, `together`, `anthropic` with `disabled=true` and blank keys.

### `airoute` (new)

id = function/template name (`chat_tutor_usercomment`). Fields: `aiservers` (ordered multi-value of `aiserver` ids), `timeoutseconds` (optional override), `description`. No row → chain is all enabled rows of the requested type by `ordering`.

### `aicalllog` (new)

One row per attempt: `functionname`, `aiserver`, `modelname`, `ms`, `promptokens`, `completiontokens`, `status` (`ok`, `error`, `timeout`, `breakeropen`, `breakerclosed`, `misconfigured`), `httpstatus`, `errormessage` (no keys, ≤500 chars), `attempt`, `datecreated`. Daily cleanup event deletes rows older than 30 days.

Per-client configuration is free: every table is per catalog.

## 2. Router and failover

### Entry points

`MediaArchive.getLlmConnection(type)` keeps its signature and returns a `RoutedLlmConnection` built from the enabled rows of that type. The router resolves the `airoute` row lazily from the function name passed to `callStructure`, `callClassifyFunction`, `callToolsFunction` and `callJson`. No caller changes. Cache key stays the type in the `llmconnection` cache; the router holds the row list and is rebuilt when older than 60 s (nothing clears the cache on admin edits).

### `RoutedLlmConnection implements LlmConnection`

Holds one delegate bean per `aiserver` row (from `connectionbean`), each with its own `HttpSharedConnection` whose socket timeout is the row's `timeoutseconds`. Call loop:

1. Resolve chain: `airoute` row if present, else enabled type rows by `ordering`.
2. Skip rows with an open breaker unless the window expired (one probe allowed).
3. Skip rows with a blank key when the type needs one; log `misconfigured`, not a breaker failure.
4. Call the delegate. Success: reset failure count, log `ok`, return.
5. Failure (non-200, connection error, socket timeout, unparsable JSON, empty message, refusal): increment failure count, open breaker at threshold, log, next row.
6. Chain exhausted: throw `OpenEditException("No AI server answered <function>; tried llamat(timeout 30s), groq(429), anthropic(breaker open)")`.

Breaker state: `ConcurrentHashMap<String, BreakerState>` keyed by `aiserver` id, JVM-local. Half-open: one probe after `breakerminutes`; success closes, failure reopens.

Read-only methods (`getModelName`, `getServerRoot`, `getApiKey`, `isReady`, `getLlmProtocol`, `getAiServerData`) answer for the first row of the chain. `renderLocalAction` and `loadInputFromTemplate` go to the first row.

### Health poll

`monitorAiServers` (15 min) keeps polling `/health` on `monitorspeed=true` rows, writes `healthms`, and closes an open breaker when a server answers again. Fix the `"Bearer " + server` header bug (should be the key).

## 3. Provider adapters

One template per function, OpenAI chat-completions shape, rendered from `ai/{protocol}/calls/{function}.json` with `$model` from the row and new `$aiserver` in the context. Adapters take the parsed map and return an `LlmResponse` whose `getMessage()` / structured result behave like `OpenAiResponse`.

| Bean | Providers | Behaviour |
|---|---|---|
| `openaiConnection` (exists) | OpenAI, Groq, OpenRouter, Together, Cerebras | `POST {serverroot}/chat/completions`, `Authorization: Bearer`. Strips llama.cpp-only keys (`chat_template_kwargs`, `cache_prompt`, `slot_id`) when `aiservertype != llama`. |
| `llamaOpenAiConnection` (exists) | llama.cpp | Pass-through, keeps `chat_template_kwargs.enable_thinking`. |
| `anthropicConnection` (new) | Anthropic | Raw HTTP `POST {serverroot}/v1/messages`, headers `x-api-key`, `anthropic-version`. `messages[role=system]` → `system`; `max_tokens` default 1024; `temperature` passes; `response_format.json_schema` → `output_config.format`; `tools` → Anthropic tool shape; `content[0].text` → `choices[0].message.content` so `OpenAiResponse` parsing is reused. `stop_reason=refusal` → failed attempt. |
| `geminiConnection`, `ollamaConnection` (exist) | | Untouched. |

`extraparams` from the row is merged last into the request, e.g. `{"thinking":{"type":"adaptive"}}` on an Anthropic row. No SDK dependency: all adapters use `HttpSharedConnection`.

Usage tokens are read from OpenAI `usage.prompt_tokens/completion_tokens` or Anthropic `usage.input_tokens/output_tokens`; missing usage is blank, never an error.

## 4. Observability, A/B, eval harness

### Observability

Admin list view for `aicalllog` under the AI settings menu: filter by function, server, status, date; columns ms, tokens, attempt. Breaker open/close events are rows too.

### A/B override

- Reorder `aiservers` on the `airoute` row in the admin UI; effective on next call.
- The eval endpoint's `aiserver` parameter (admin only) calls one named row with no failover; used by curl and the eval script, never by the app.

### Eval harness

Generic runner in `plugins/finder/tools/llmeval/`; per-product golden sets elsewhere (TestU: `plugins/testu/tools/llmeval/`).

- `golden.jsonl`: ~30 prompts with `function`, `input` (the fields the skill fills: learner prompt, question, options, explanation, reference excerpts), `expected` (must-cite, must-not-say, language, max words).
- `run.sh <golden.jsonl> <aiserver-id...>`: for each prompt × server, POST to admin-only `services/llm/evalcall.json` with `aiserver=<id>`; endpoint renders the template and calls that one row with no failover. Records ms, tokens, reply, log id to `results/<date>-<aiserver>.jsonl`. `--dry-run` renders only.
- `judge.sh`: deterministic checks (JSON parses, word cap, language, cites present, forbidden phrases), then an LLM judge under function `llm_eval_judge` (its `airoute` row points at the Anthropic row, model `claude-opus-5`) scoring correctness, groundedness, tone 1–5 with a one-line reason.
- `report.sh`: Markdown table per run: pass rate, median and p95 ms, mean judge score, cost from tokens × `prices.json`.

Local runs hit dev Tomcat as admin/admin. Live runs use a dedicated eval admin account on the Minsur server.

## 5. Error handling

- Chain exhausted → single `OpenEditException` naming function and every server tried; `AssistantManager` already catches, stores an error row, broadcasts. The app shows its existing error state.
- No per-row retries; attempts are bounded by chain length. 429 and 5xx fail over. 400 also fails over but logs the response body (adapter bug, not outage).
- Timeouts: per-row `timeoutseconds`, route override wins; tutor functions 30 s, batch functions may set up to the 1200 s ceiling. Timeouts count toward the breaker.
- Missing config (blank key, no enabled rows) → `misconfigured` log or exception "no enabled aiserver of type X"; never a breaker failure.
- Unparsable reply in schema mode → fail over; if the last row fails, the exception carries the raw text truncated to 500 chars.
- `aicalllog` writes are try/catch; failure logs to Tomcat, never blocks an answer.
- Keys never appear in logs, exceptions, or the admin list (masked).

## 6. Testing, rollout, upstream PR

### Tests (JUnit in `plugins/finder`, no live providers)

- Router with fake delegates: primary ok; primary timeout → second ok; breaker trips after N; half-open probe; misconfigured skipped; chain-exhausted message.
- `anthropicConnection` request translation against a fixture of the tutor template; `openaiConnection` key stripping.
- Eval `--dry-run` renders every golden prompt.

### Rollout

1. Data model + admin views. No behaviour change (`enabled` defaults true, chains resolve to today's single row).
2. Router with llamat only. Identical behaviour except per-row timeout and the header fix.
3. Groq and Anthropic rows on dev; run eval locally; choose order.
4. Rows on the Minsur server; `airoute` for `chat_tutor_usercomment` only; watch `aicalllog` a day; widen.

### Deploy prerequisite

Production plugin origins point at entermedia-community, so our fork never runs on Minsur. Decision: repoint the server's plugin origins at our fork now; upstream merge later. Otherwise step 4 waits on EnterMedia review.

### Upstream PR

One PR per repo (finder, catalog, mediadb), branch `llm-routing`: data model, router, adapters, admin views, eval harness, tests, `baseaiserver.xml` example rows with blank keys. Excluded: TestU golden set, any `airoute` rows, keys. PR text explains the config model and links an eval report.

## Deviations settled during planning (2026-09-15)

- No `X-AiServer` header: the tutor's LLM call runs on the `monitorchats` event thread, so a request header cannot reach the router. A/B for real traffic is the `airoute` row; single-server calls use the eval endpoint's `aiserver` parameter.
- Eval endpoint path is `services/llm/evalcall.json` in the mediadb plugin (`services/ai/` is a virtual directory owned by `JsonDataModule.handleAiFunction`), so mediadb is the third repo in the PR.
- `aicalllog` has no `user` column (no request user on the event thread).
- `AnthropicConnection.getLlmProtocol()` returns `openai` so the existing `ai/openai` → `ai/default` template fallback applies; no new template directory.
- Router instances are rebuilt after 60 s instead of on cache clears.
- Admin views: `airoute` and `aicalllog` use the generic admin data manager; no bespoke list view.
- aiserver.enabled became aiserver.disabled and every numeric config treats 0/blank as default: Elasticsearch stores a missing boolean as false and a missing number as 0, so an existing row would otherwise read as disabled with a 1 s timeout and a breaker that opens on the first call.
