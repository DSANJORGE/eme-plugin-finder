# LLM routing 2: salvage onto FailoverLlmConnection — design

Date: 2026-09-21. Repos: `plugins/finder`, `plugins/catalog`, `plugins/mediadb`, branch `llm-routing-2` off `main`. Supersedes the `llm-routing` branch and `2026-09-15-llm-routing-design.md`.

## Why

Two implementations of provider failover now exist. `main` (2026-09-21) ships `org.entermediadb.ai.llm.FailoverLlmConnection`: a ladder of every enabled `aiserver` row of a type, per-row breaker, built once per type by `MediaArchive.getLlmConnection`, plus `OpenAiConnection` fixes (`stripLlamaExtensions`, `salvageStructure`, a 429 retry) and the health-poll header fix. The `llm-routing` branch (2026-09-15) ships `RoutedLlmConnection` with the same idea plus six things `main` lacks. Decision: keep `main`'s router, port the six things onto it, drop `RoutedLlmConnection`.

The six: (1) Anthropic adapter, (2) eval endpoint + harness + judge, (3) `aicalllog`, (4) `airoute`, (5) per-row `timeoutseconds`/`extraparams` honoured by the connection, (6) tests and connection-layer fixes (ThreadLocal timeout, catch `Exception` not `RuntimeException`, header parity, single `chat()` path).

Goal and non-goals are unchanged from the 2026-09-15 spec: per-catalog choice of server per function with failover, generic eMe infrastructure, keys only in `aiserver` rows and never committed.

## 1. Router: `FailoverLlmConnection`, extended

Class, package, constructor, `add(Data, LlmConnection)`, `Entry`, breaker (default 3 failures / 2 min, one probe after the window, single record always tried) and every `LlmConnection` method stay as on `main`. Additions:

- **`setMediaArchive(MediaArchive)`**, set by `MediaArchive.getLlmConnection`. Null in unit tests: then no `airoute` lookup and no log rows.
- **Route per function.** `failover(inWhat, call)` first asks `route(inWhat)` for the `airoute` row (`getSearcher("airoute").searchById(inWhat)`, uncached, one lookup per call). If the row lists `aiservers`, the attempt order is those ids, in that order, skipping ids whose row is missing or `disabled`. Ids not in the ladder get an `Entry` built on demand from the row's `connectionbean` (`entryFor(String id)`, kept in a map for the life of the connection, so a route may name a row of another type). No route or an empty list: the ladder as today.
- **Route timeout.** `airoute.timeoutseconds` > 0 is applied to the entry's connection via `BaseLlmConnection.setTimeoutOverride(seconds)` before the call and cleared in a `finally`. Zero, blank or no route: the connection uses the row's own `timeoutseconds` (section 3).
- **Failure detection** is `main`'s (exception or empty payload) but the catch is `Exception`, not `RuntimeException`, and a `SocketTimeoutException`/`ConnectTimeoutException` anywhere in the cause chain is status `timeout`.
- **One `aicalllog` row per attempt** through `logAttempt(Data server, String function, String status, long ms, int attempt, String error, LlmResponse response)`: statuses `ok`, `error`, `timeout`, `breakeropen` (skipped without a call). `fillLogRow` sets `functionname`, `aiserver`, `modelname`, `status`, `ms`, `attempt`, `datecreated`, `errormessage` (≤500 chars, never a key), `httpstatus` parsed from an `HTTP nnn` in the error, `prompttokens`/`completiontokens` from `usage`. Writes are try/catch; a failed write is a Tomcat log line, never a failed answer.
- **Chain exhausted:** rethrow the last exception as today; the log rows carry the per-server detail.

Not ported from the branch: the 60 s rebuild (edits to `aiserver` rows take effect after Clear Caches or the health poll's cache clear, as on `main`), the static delegate cache (no leak without the rebuild), the blank-key `misconfigured` status (a blank key is an ordinary provider 401), `breakerclosed` rows, and closing breakers from the health poll (the poll already clears the `llmconnection` cache, which rebuilds every breaker).

## 2. `MediaArchive.getLlmConnection`

Unchanged except `failover.setMediaArchive(this)` after construction, and: an `embedding` type ladder keeps only its first enabled row (vectors from two models are not comparable; an `airoute` row can still name another server).

## 3. Connection layer

### `BaseLlmConnection` (from the branch, unchanged)

`DEFAULT_TIMEOUT_SECONDS = 30`, `MAX_TIMEOUT_SECONDS = 1200`, ThreadLocal `setTimeoutOverride(Integer)`, `getTimeoutSeconds()` (override, else row `timeoutseconds` > 0, else 30; capped at 1200), `execute(HttpRequestBase)` applying that socket timeout, `mergeExtraParams(JSONObject)` (row `extraparams` JSON object merged last; invalid JSON is logged and ignored), `applyLlmHeaders(method, headers)` (x-customerkey plus shared and extra headers, null values skipped) used by `callJson`, which now goes through `execute`.

### `OpenAiConnection` (branch structure, `main`'s behaviour folded in)

- `prepareRequest(JSONObject)`: for protocol other than `llama`, translate `chat_template_kwargs.enable_thinking=false` to `reasoning_effort="low"` when unset (from `main`), remove `chat_template_kwargs`, `cache_prompt`, `slot_id`, `id_slot`, `n_probs`, `min_keep`; then `mergeExtraParams`. `LlamaOpenAiConnection` (protocol `llama`) keeps everything.
- `loadCallPayload(context, function)`: renders `/{mediadb}/ai/{protocol}/calls/{function}.json` with catalog fallback, `$model` and a key-free `$aiserver` in the context, honours `jsonfilename`, returns `prepareRequest(payload)`.
- `chat(JSONObject)`: one `POST {serverroot}/chat/completions` through `execute`, Bearer header, `applyLlmHeaders`. On 429, up to two retries 2 s apart (from `main`). On any other non-200, read the body once; `salvageStructure(body, request)` (from `main`, now taking the body string) may turn a groq `json_validate_failed` into a reply when the schema has exactly one required property; otherwise throw `OpenEditException("LLM HTTP <status> from <root>: <body≤500>")`.
- `callStructure`, `callToolsFunction`, `callClassifyFunction`, `callSmartCreatorAiAction` and `callCreateFunction` all end in `chat(...)`; `runPageAsInput` uses `execute`. `createImage` and `callRagFunction` keep `callJson`.

### `AnthropicConnection extends OpenAiConnection` (branch, unchanged)

Bean `anthropicConnection` in `plugin.xml`. Overrides `chat`: `POST {serverroot}/messages` with `x-api-key` and `anthropic-version: 2023-06-01`; `toAnthropicRequest` (system messages → `system`, `max_tokens` default 1024 or from `max_completion_tokens`, `stop` → `stop_sequences`, temperature/top_p/top_k dropped, `response_format.json_schema` → `output_config.format`, tools and `tool_choice` translated, OpenAI content parts → text/image blocks, empty messages → one placeholder user turn); `toOpenAiResponse` back to a chat.completions shape with `usage.prompt_tokens/completion_tokens`; `stop_reason=refusal` is a failed attempt. Protocol stays `openai` so templates are shared. `extraparams` such as `{"thinking":{"type":"disabled"}}` reach the request through the `thinking` key.

## 4. Eval (branch, moved)

- `org.entermediadb.ai.llm.LlmEvalModule.evalCall`: administrators only; params `function`, `aiserver`, `input` (JSON of context values), `dryrun`; refuses a `disabled` row; builds the row's bean directly (no failover) and answers `{ok, message, payload, usage, ms}` or `{ok:false, error}`. Bean `LlmEvalModule` in `plugin.xml`; mediadb `html/services/llm/evalcall.{xconf,json}`.
- mediadb judge template `html/ai/default/calls/llm_eval_judge.json` (generic rubric, optional `$rubric`, `max_tokens` 1500, json_schema reply).
- finder `tools/llmeval/{run.sh,judge.sh,report.sh,prices.json}` as on the branch (`RUN_DELAY`, `DRYRUN`, `EME_BASE`, `EME_ADMIN_PW`).
- TestU golden set and `harvest.py` already live on `plugins/testu` main; not part of this PR.

## 5. Catalog data

- `aicalllog.xml` and `airoute.xml` fields as on the branch (`errormessage`, `description` and `extraparams` are `index="true" type="text"`: eMe drops `index="false"` fields from Elasticsearch).
- `aiserver.xml` on `main` already has `disabled`, `timeoutseconds`, `breakerfailures`, `breakerminutes` (default 2), `healthms`, `extraparams`. No change.
- `llmtype.xml` gains `anthropic`. `baseaiserver.xml` gains disabled example rows `anthropic` (claude-sonnet-5), `groq` (openai/gpt-oss-120b, `extraparams` `{"max_tokens":2000,"reasoning_effort":"low"}`) and `openrouter` (openai/gpt-oss-120b) with blank keys.
- Event `events/llm/cleanaicalllog.xconf` (daily, 04:00) running `events/scripts/llm/cleanaicalllog.groovy` → `AiCallLogCleaner.deleteOlderThanDays(archive, 30)` (package `org.entermediadb.ai.llm`).

## 6. Health poll

`main`'s fix stays (Bearer key, `-1` on failure, writes `healthms`, clears the `llmconnection` cache). Nothing added.

## 7. Tests (JUnit 3 `TestCase`, no server)

- `FailoverLlmConnectionTest` with scripted fake connections and a subclass overriding `route` and `logAttempt`: primary answers; error and timeout fail over with the right statuses; empty payload fails over; breaker opens after N and logs `breakeropen`; single record tried even when open; route reorders, skips disabled and unknown ids, applies then clears the timeout override; route naming a row outside the ladder builds an entry on demand; zero config values mean defaults; `fillLogRow` sets every field and cuts the error at 500; `httpStatusOf`; chain exhausted rethrows the last exception.
- `OpenAiConnectionPrepareTest`: strips llama keys, translates `enable_thinking:false`, keeps them for `llama`, merges/ignores `extraparams`, timeout default/row/cap/override; `salvageStructure` recovers one-property schemas and returns null otherwise.
- `AnthropicConnectionTest`: unchanged from the branch.

Run with the worktree scripts `compile.sh`/`test.sh` (javac into a scratch `build/`, `junit.textui.TestRunner`), never the shared `build/` that Tomcat loads.

## 8. Rollout

1. Land `llm-routing-2` in the three repos; open PRs against the fork `main`s; close the `llm-routing` PRs.
2. After the next local Tomcat restart: Reindex `aiserver`, `aicalllog`, `airoute`; confirm rows in `aicalllog` on a tutor turn; recreate `airoute` `chat_tutor_usercomment`; run the 23-prompt harness against llamat, anthropic-sonnet and anthropic-haiku.
3. Production origins → forks; keys entered on that server by the user.
