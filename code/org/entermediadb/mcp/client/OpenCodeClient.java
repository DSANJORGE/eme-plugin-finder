package org.entermediadb.mcp.client;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.jsonrpc.JsonRpcScanner;
import org.entermediadb.scripts.ScriptLogger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.CatalogEnabled;
import org.openedit.Data;
import org.openedit.ModuleManager;
import org.openedit.util.HttpSharedConnection;
import org.openedit.util.JSONParser;

/**
 * Client for a running OpenCode v2 server (the paired web interface started by {@code opencode
 * pair}). All HTTP calls use the {@code /api/*} surface; see the "opencode-v2-api" skill for the
 * full endpoint and event reference.
 *
 * <p>Connects to the HTTP API for session and message operations, and maintains a background
 * listener on the {@code /api/event} SSE stream so callers can wait for server events such as
 * the {@code session.execution.succeeded|failed|interrupted} turn-end events after a prompt. The
 * v2 prompt endpoint is fire-and-forget, so blocking calls like {@link #sendMessage(String,
 * String)} post the prompt and then wait for the turn to end via the event stream.
 *
 * <p>Every v2 JSON response is wrapped as {@code {"data": ...}} (the one exception is
 * {@code GET /api/info}); this client unwraps that envelope and normalizes v2 message shapes
 * back to the v1 {@code {info: {role}, parts: [{type, text}]}} shape so callers keep working
 * unchanged.
 *
 * <p>Authentication: every request carries {@code Authorization: Basic base64("opencode:" +
 * password)}. The password is the pairing key shown by {@code opencode pair} (or
 * {@code opencode service get password}). It is persisted in the opencode service state file
 * ({@code ~/.local/state/opencode/service.json}) and stays static across server restarts; it only
 * changes when explicitly replaced via {@code opencode service set password <value>} (which stops
 * the running server) or when the state file is deleted. The key is typically stored in the
 * {@code aiserver} data list ({@code serverapikey} field) in base64 form, loaded with
 * {@link #loadBasicAuthFromAiServer()}.
 */
public class OpenCodeClient implements CatalogEnabled
{
    protected static final String DEFAULT_SERVER_URL = "http://127.0.0.1:49374";
    protected static final long DEFAULT_EVENT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

    // The v2 SSE stream sends ": heartbeat" comment lines while idle (skipped by
    // JsonRpcScanner); keep a long socket timeout as a safety net for quiet servers.
    protected static final int SSE_SOCKET_TIMEOUT_MS = 30 * 60 * 1000;

    // How long sendMessage blocks for the turn to end after posting the prompt.
    protected static final long SEND_MESSAGE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

    protected final List<JSONObject> fieldRecentEvents = new ArrayList<>();
    protected long fieldEventSeq = 0;
    protected String fieldServerUrl = DEFAULT_SERVER_URL;
    protected HttpSharedConnection fieldConnection;
    protected Thread listener;
    protected volatile boolean fieldListening = false;
    protected volatile CloseableHttpResponse fieldEventResponse;

    // Basic auth credentials for the OpenCode server (see "opencode pair"). The username is
    // fixed by the server; the password is generated at pairing time and typically kept in the
    // aiserver data list in base64 form.
    protected String fieldUsername = "opencode";
    protected String fieldPassword;
    protected ModuleManager fieldModuleManager;
    protected String fieldCatalogId;
    protected MediaArchive fieldMediaArchive;

    public String getServerUrl()
    {
        return fieldServerUrl;
    }

    public void setServerUrl(String inServerUrl)
    {
        fieldServerUrl = inServerUrl;
    }

    public ModuleManager getModuleManager()
    {
        return fieldModuleManager;
    }

    /**
     * Injected by the "openCodeClient" bean definition (see plugin.xml); used to look up the
     * aiserver data list for {@link #loadBasicAuthFromAiServer(String)}.
     */
    public void setModuleManager(ModuleManager inModuleManager)
    {
        fieldModuleManager = inModuleManager;
    }

    /**
     * Set automatically by the module manager when this bean is instantiated (see
     * {@link ModuleManager#loadBean(String, String)}); identifies the catalog whose
     * {@code mediaArchive} bean serves the {@code aiserver} data list.
     */
    public String getCatalogId()
    {
        return fieldCatalogId;
    }

    public void setCatalogId(String inCatalogId)
    {
        fieldCatalogId = inCatalogId;
    }

    public MediaArchive getMediaArchive()
    {
        if (fieldMediaArchive == null)
        {
            fieldMediaArchive = (MediaArchive) getModuleManager().getBean(getCatalogId(), "mediaArchive");
        }
        return fieldMediaArchive;
    }

    public void setMediaArchive(MediaArchive inMediaArchive)
    {
        fieldMediaArchive = inMediaArchive;
    }

    /**
     * Loads Basic auth credentials from an entry of the {@code aiserver} data list. The entry may
     * carry either
     * <ul>
     * <li>{@code basicauth}: a pre-encoded token, used verbatim as the header value after
     * "Basic" (this is base64("username:password") as printed by {@code opencode pair});</li>
     * <li>{@code basicuser} + {@code basicpass}: plain values that are encoded here as
     * base64(username:password). Either value may itself be stored base64-encoded, in which case
     * it is decoded first.</li>
     * </ul>
     * Falls back to the entry's {@code serverapikey} when neither of the above is present.
     */
    public void loadBasicAuthFromAiServer()
    {
        Data server = getMediaArchive().getData("aiserver", "opencode");
        String token = stringOf(server.get("serverapikey"));
        getConnection().putSharedHeader("Authorization", "Basic " + token);
    }

    public HttpSharedConnection getConnection()
    {
        if (fieldConnection == null)
        {
            fieldConnection = new HttpSharedConnection();
            // Must be set before the shared client is first built.
            fieldConnection.setSocketTimeout(SSE_SOCKET_TIMEOUT_MS);
            loadBasicAuthFromAiServer();
        }
        return fieldConnection;
    }

    /**
     * Checks server health and starts the background event listener.
     */
    public void connectToServer() 
    {
        if( isListening() == false)
        {
            startListeningInBackground();
        }
    }

    public void disconnect()
    {
        fieldListening = false;
        if (listener != null)
        {
            listener.interrupt();
        }
        synchronized (fieldRecentEvents)
        {
            fieldRecentEvents.notifyAll();
        }
        CloseableHttpResponse resp = fieldEventResponse;
        if (resp != null)
        {
            fieldEventResponse = null;
            getConnection().release(resp);
        }
    }

    public boolean isListening()
    {
        return fieldListening;
    }

    // ---------------------------------------------------------------------
    // Event stream
    // ---------------------------------------------------------------------

    public void startListeningInBackground()
    {
        fieldListening = true;
        listener = new Thread("opencode-client-listener")
        {
            @Override
            public void run()
            {
                startListening();
            }
        };
        listener.setDaemon(true);
        listener.start();
    }

    /**
     * Blocks reading the {@code /api/event} SSE stream, dispatching each event to
     * {@link #eventReceived(JSONObject)}. Reconnects with a short delay while
     * {@link #fieldListening} remains true.
     *
     * <p>The v2 wire format carries JSON only on {@code data:} lines (the event type is the
     * {@code type} field inside that JSON); idle keep-alives arrive as {@code : heartbeat}
     * comment lines, which JsonRpcScanner skips. The stream is global to the server, so
     * session-scoped events must be filtered by {@code data.sessionID}.
     */
    public void startListening()
    {
        while (fieldListening)
        {
            CloseableHttpResponse response = null;
            JsonRpcScanner scanner = null;
            try
            {
                HttpGet method = new HttpGet(getServerUrl() + "/api/event");
                method.addHeader("Accept", "text/event-stream");
                response = getConnection().sharedExecute(method);
                fieldEventResponse = response;

                int status = response.getStatusLine().getStatusCode();
                if (status < 200 || status >= 300)
                {
                    throw new Exception("Unexpected status from /api/event: " + status);
                }

                InputStream input = response.getEntity().getContent();
                scanner = new JsonRpcScanner(input, "UTF-8");
                while (fieldListening)
                {
                    JSONObject event = scanner.nextEvent();
                    if (event == null)
                    {
                        break; // stream closed
                    }
                    eventReceived(event);
                }
            }
            catch (Exception e)
            {
                if (fieldListening)
                {
                    System.err.println("OpenCodeClient event stream error: " + e.getMessage());
                }
            }
            finally
            {
                fieldEventResponse = null;
                if (scanner != null)
                {
                    try
                    {
                        scanner.close();
                    }
                    catch (Exception ignored)
                    {
                    }
                }
                getConnection().release(response);
            }

            if (fieldListening)
            {
                try
                {
                    TimeUnit.SECONDS.sleep(2);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    protected void eventReceived(JSONObject inEvent)
    {
        synchronized (fieldRecentEvents)
        {
            inEvent.put("_seq", ++fieldEventSeq);
            fieldRecentEvents.add(inEvent);
            if (fieldRecentEvents.size() > 1000)
            {
                fieldRecentEvents.remove(0);
            }
            fieldRecentEvents.notifyAll();
        }
    }

    public List<JSONObject> getRecentEvents()
    {
        synchronized (fieldRecentEvents)
        {
            return new ArrayList<>(fieldRecentEvents);
        }
    }

    /**
     * Waits for an event whose {@code type} (or SSE {@code _event} name) matches the given type.
     */
    public JSONObject waitForEvent(String inType, long inTimeoutMs) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + inTimeoutMs;
        synchronized (fieldRecentEvents)
        {
            while (true)
            {
                for (JSONObject event : fieldRecentEvents)
                {
                    String type = stringOf(event.get("type"));
                    if (type == null)
                    {
                        type = stringOf(event.get("_event"));
                    }
                    if (inType.equals(type))
                    {
                        return event;
                    }
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0)
                {
                    return null;
                }
                fieldRecentEvents.wait(Math.min(1000, remaining));
            }
        }
    }

    /**
     * Waits for a turn-end event ({@code session.execution.succeeded}, {@code .failed} or
     * {@code .interrupted}) matching the given session id. v2 has no single idle event; all
     * three end a prompt turn. Returns the matching event, or null on timeout.
     */
    public JSONObject waitForSessionIdle(String inSessionId, long inTimeoutMs) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + inTimeoutMs;
        synchronized (fieldRecentEvents)
        {
            while (true)
            {
                for (JSONObject event : fieldRecentEvents)
                {
                    if (!isExecutionEndFor(event, inSessionId))
                    {
                        continue;
                    }
                    return event;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0)
                {
                    return null;
                }
                fieldRecentEvents.wait(Math.min(1000, remaining));
            }
        }
    }

    /**
     * True when inEvent is one of the v2 turn-end events ({@code session.execution.succeeded},
     * {@code .failed} or {@code .interrupted}) whose {@code data.sessionID} matches.
     */
    protected static boolean isExecutionEndFor(JSONObject inEvent, String inSessionId)
    {
        String type = stringOf(inEvent.get("type"));
        if (!"session.execution.succeeded".equals(type) && !"session.execution.failed".equals(type)
                && !"session.execution.interrupted".equals(type))
        {
            return false;
        }
        JSONObject data = asObject(inEvent.get("data"));
        return data != null && inSessionId.equals(stringOf(data.get("sessionID")));
    }

    // ---------------------------------------------------------------------
    // Health and sessions
    // ---------------------------------------------------------------------

    /**
     * Returns server info ({@code version}, {@code pid}, {@code urls}, {@code paths}). This is
     * the v2 replacement for the old health check and the one API response that is NOT wrapped
     * in a {@code data} envelope.
     */
    public JSONObject getHealth() 
    {
        CloseableHttpResponse response = null;
        try
        {
            response = getConnection().sharedGet(getServerUrl() + "/api/info");
            return asObject(request(response));
        }
        finally
        {
            getConnection().release(response);
        }
    }

    /**
     * Creates a new session. inFolder becomes the session's working directory
     * ({@code location.directory}); v2 sessions run inside the paired server, so this is where
     * the agent's tools operate. Returns the created session object (including {@code id}).
     */
    public JSONObject createSession(String inTitle, String inFolder) 
    {
        String template = """
        {
          "title": "${title}",
          "agent": "build",
          "location": { "directory": "${directory}" },
          "permissions": [
            { "action": "*", "resource": "*", "effect": "allow" },
            { "action": "external_directory", "resource": "*", "effect": "allow" },
            { "action": "read", "resource": "*.env", "effect": "allow" },
            { "action": "read", "resource": "*.env.*", "effect": "allow" }
          ]
        }
        """;

        String jsonStr = template
            .replace("${title}", inTitle)
            .replace("${directory}", inFolder);

        JSONObject json = (JSONObject) new JSONParser().parse(jsonStr);
        return (JSONObject) unwrapData(postJson("/api/session", json));
    }

    public Collection<JSONObject> listSessions() 
    {
        CloseableHttpResponse response = null;
        try
        {
            response = getConnection().sharedGet(getServerUrl() + "/api/session");
            Object parsed = request(response);
            if (parsed instanceof JSONObject)
            {
                parsed = ((JSONObject) parsed).get("data");
            }
            if (parsed instanceof JSONArray)
            {
                List<JSONObject> sessions = new ArrayList<>();
                for (Object item : (JSONArray) parsed)
                {
                    JSONObject session = asObject(item);
                    if (session != null)
                    {
                        sessions.add(session);
                    }
                }
                return sessions;
            }
            return new ArrayList<>();
        }
        finally
        {
            getConnection().release(response);
        }
    }

    public JSONObject getSession(String inSessionId) 
    {
        CloseableHttpResponse response = null;
        try
        {
            response = getConnection().sharedGet(getServerUrl() + "/api/session/" + inSessionId);
            return (JSONObject) unwrapData(request(response));
        }
        finally
        {
            getConnection().release(response);
        }
    }

    // ---------------------------------------------------------------------
    // Messages
    // ---------------------------------------------------------------------

    /**
     * Sends a message and blocks until the assistant response is complete. Returns the full
     * message object ({@code info} plus {@code parts}).
     *
     * <p>The v2 prompt endpoint ({@code POST /api/session/{id}/prompt}) is fire-and-forget, so
     * this posts the prompt and then waits for the turn-end event
     * ({@code session.execution.succeeded|failed|interrupted}) on the SSE stream before
     * fetching and returning the assistant's reply. Returns null if the server rejects the
     * prompt (unknown session, conflict, ...) or if no assistant message exists when the turn
     * ends (e.g. it failed or the wait timed out).
     */
    public JSONObject sendMessage(String inSessionId, String inText) 
    {
        long seqBefore = currentEventSeq();
        Object userMessage = postJson("/api/session/" + inSessionId + "/prompt", promptBody(inText));
        if (userMessage == null)
        {
            return null;
        }
        try
        {
            waitForExecutionEndSince(inSessionId, seqBefore, SEND_MESSAGE_TIMEOUT_MS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        return lastAssistantMessage(getMessages(inSessionId));
    }

    /**
     * Sends a prompt asynchronously. Returns immediately once the server has accepted it (the
     * v2 prompt endpoint never blocks); wait for completion with
     * {@link #waitForSessionIdle(String, long)}.
     */
    public void sendPromptAsync(String inSessionId, String inText) 
    {
        postJson("/api/session/" + inSessionId + "/prompt", promptBody(inText));
    }

    /**
     * Aborts any in-flight work on the session (v2 {@code POST /api/session/{id}/interrupt}).
     */
    public void abortSession(String inSessionId) 
    {
        postJson("/api/session/" + inSessionId + "/interrupt", new JSONObject());
    }

    /**
     * Returns the session's messages in oldest-first order, each normalized to the v1 shape
     * {@code {info: {role, ...}, parts: [{type, text, ...}]}}. The v2 list is newest-first and
     * leads with an {@code idle} marker entry, which is dropped here.
     */
    public List<JSONObject> getMessages(String inSessionId) 
    {
        CloseableHttpResponse response = null;
        try
        {
            response = getConnection().sharedGet(getServerUrl() + "/api/session/" + inSessionId + "/message");
            Object parsed = request(response);
            if (parsed instanceof JSONObject)
            {
                parsed = ((JSONObject) parsed).get("data");
            }
            List<JSONObject> messages = new ArrayList<>();
            if (parsed instanceof JSONArray)
            {
                for (Object item : (JSONArray) parsed)
                {
                    JSONObject message = normalizeV2Message(asObject(item));
                    if (message != null)
                    {
                        messages.add(message);
                    }
                }
            }
            // v2 lists newest-first; callers expect oldest-first.
            Collections.reverse(messages);
            return messages;
        }
        finally
        {
            getConnection().release(response);
        }
    }

    /**
     * Body for the v2 prompt endpoint: a plain text payload (v1's {@code parts} array is gone).
     */
    protected JSONObject promptBody(String inText)
    {
        JSONObject body = new JSONObject();
        body.put("text", inText);
        return body;
    }

    /**
     * Waits, like {@link #waitForSessionIdle(String, long)}, but only for turn-end events that
     * arrived after inMinSeq, so a stale event from an earlier turn cannot satisfy the wait.
     */
    protected JSONObject waitForExecutionEndSince(String inSessionId, long inMinSeq, long inTimeoutMs)
            throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + inTimeoutMs;
        synchronized (fieldRecentEvents)
        {
            while (true)
            {
                for (JSONObject event : fieldRecentEvents)
                {
                    if (longOf(event.get("_seq")) > inMinSeq && isExecutionEndFor(event, inSessionId))
                    {
                        return event;
                    }
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0)
                {
                    return null;
                }
                fieldRecentEvents.wait(Math.min(1000, remaining));
            }
        }
    }

    protected long currentEventSeq()
    {
        synchronized (fieldRecentEvents)
        {
            return fieldEventSeq;
        }
    }

    /**
     * Finds the most recent assistant message in a normalized (oldest-first) list and returns
     * it, or null when the list holds none.
     */
    protected static JSONObject lastAssistantMessage(List<JSONObject> inMessages)
    {
        if (inMessages == null)
        {
            return null;
        }
        for (int i = inMessages.size() - 1; i >= 0; i--)
        {
            JSONObject message = inMessages.get(i);
            Object infoObj = message.get("info");
            if (infoObj instanceof JSONObject && "assistant".equals(((JSONObject) infoObj).get("role")))
            {
                return message;
            }
        }
        return null;
    }

    /**
     * Converts one v2 message entry into the v1 {@code {info, parts}} shape, or null for
     * entries that carry no conversation content (the leading {@code idle} marker).
     *
     * <ul>
     * <li>v2 user: {@code {id, sessionID, time, type:"user", payload:{text}, delivery}}</li>
     * <li>v2 assistant: {@code {id, time, type:"assistant", agent, model, content:[{type:"reasoning"|"text"|tool, ...}], snapshot}}</li>
     * </ul>
     */
    protected static JSONObject normalizeV2Message(JSONObject inMessage)
    {
        if (inMessage == null)
        {
            return null;
        }
        String type = stringOf(inMessage.get("type"));
        JSONObject info = new JSONObject();
        JSONArray parts = new JSONArray();
        if ("user".equals(type))
        {
            info.put("role", "user");
            JSONObject payload = asObject(inMessage.get("payload"));
            if (payload != null && payload.get("text") != null)
            {
                JSONObject part = new JSONObject();
                part.put("type", "text");
                part.put("text", payload.get("text"));
                parts.add(part);
            }
        }
        else if ("assistant".equals(type))
        {
            info.put("role", "assistant");
            Object content = inMessage.get("content");
            if (content instanceof JSONArray)
            {
                for (Object item : (JSONArray) content)
                {
                    JSONObject part = asObject(item);
                    if (part != null)
                    {
                        parts.add(part);
                    }
                }
            }
        }
        else
        {
            return null; // idle marker or unknown entry type
        }
        for (String key : new String[] { "id", "time" })
        {
            if (inMessage.get(key) != null)
            {
                info.put(key, inMessage.get(key));
            }
        }
        JSONObject normalized = new JSONObject();
        normalized.put("info", info);
        normalized.put("parts", parts);
        return normalized;
    }

    // ---------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------

    protected Object postJson(String inPath, JSONObject inBody)
    {
        CloseableHttpResponse response = null;
        try
        {
            response = getConnection().sharedPostWithJson(getServerUrl() + inPath, inBody);
            return request(response);
        }
        finally
        {
            getConnection().release(response);
        }
    }

    /**
     * Posts a JSON body and returns the HTTP status code, or -1 when the request fails. Used by
     * endpoints whose success is signaled by status alone (the v2 permission reply answers with
     * 204 and an empty body).
     */
    protected int postJsonStatus(String inPath, JSONObject inBody)
    {
        CloseableHttpResponse response = null;
        try
        {
            response = getConnection().sharedPostWithJson(getServerUrl() + inPath, inBody);
            return response.getStatusLine().getStatusCode();
        }
        catch (Exception e)
        {
            return -1;
        }
        finally
        {
            getConnection().release(response);
        }
    }

    /**
     * Unwraps the v2 {@code {"data": ...}} response envelope. {@code GET /api/info} is the one
     * response that is not wrapped, so values without a {@code data} key are returned unchanged.
     */
    protected static Object unwrapData(Object inParsed)
    {
        JSONObject envelope = asObject(inParsed);
        return envelope != null && envelope.get("data") != null ? envelope.get("data") : inParsed;
    }

    protected Object request(CloseableHttpResponse inResponse) 
    {
        if (inResponse.getEntity() == null)
        {
            return null; // e.g. 204 No Content from the permission reply endpoint
        }
        String text = getConnection().parseText(inResponse);
        if (text == null || text.length() == 0)
        {
            return null;
        }
        JSONParser parser = new JSONParser();
        if (text.trim().startsWith("["))
        {
            return parser.parseJSONArray(text);
        }
        return parser.parse(text);
    }

    protected static JSONObject asObject(Object inValue)
    {
        return inValue instanceof JSONObject ? (JSONObject) inValue : null;
    }

    protected static String stringOf(Object inValue)
    {
        return inValue == null ? null : inValue.toString();
    }

    Map<String, SessionStatus> sessionMap = new HashMap<>(); //Expire old ones?

    public SessionStatus startSessionId(Data agentJobStep, String workingpath, String query) {
        // Implementation for starting a session with the given parameters
        String title = query.length() > 200 ? query.substring(0, 200) : query;

        JSONObject session = createSession(title,workingpath);
        String sessionId = (String)session.get("id");
        //Make a map of ids to sessions
        SessionStatus status = new SessionStatus();
        status.setSessionId(sessionId);
        status.setStartDate(new Date());
        status.setAgentJobStep(agentJobStep); // You can set this to the appropriate Data object if available
        status.setCompleted(false);
        // Only events from this point on are relevant to this session's run.
        synchronized (fieldRecentEvents)
        {
            status.setLastEventSeq(fieldEventSeq);
        }
        sessionMap.put(agentJobStep.getId(), status);
        sendPromptAsync(sessionId, query);
        return status;
    }

    /**
     * Applies every not-yet-seen {@code /api/event} to the session's status, in the order they
     * arrived, and blocks for up to inTimeoutMs waiting for more if nothing conclusive has
     * happened yet.
     *
     * <p>Per the v2 event protocol (see the "opencode-v2-api" skill): a turn ends with one of
     * {@code session.execution.succeeded}, {@code .failed} or {@code .interrupted}. A
     * {@code permission.asked} event means the agent is blocked on a tool call awaiting approval,
     * so a turn-end event only counts as "done" when no permission is pending (the agent resumes
     * and the turn ends again once the reply is sent via
     * {@link #replyToPermission(String, String, String)}). A {@code permission.replied} event
     * clears the pending permission once it's answered.
     */
    public SessionStatus advanceSession(ScriptLogger inLog, String agentJobStepId, long inTimeoutMs) throws InterruptedException
    {
        SessionStatus status = loadStatus(agentJobStepId);
        if (status == null)
        {
            return null;
        }
        long deadline = System.currentTimeMillis() + inTimeoutMs;
        synchronized (fieldRecentEvents)
        {
            while (true)
            {
                for (JSONObject event : fieldRecentEvents)
                {
                    long seq = longOf(event.get("_seq"));
                    if (seq <= status.getLastEventSeq())
                    {
                        continue;
                    }
                    applySessionEvent(inLog,status, event);
                    status.setLastEventSeq(seq);
                }
                if (status.isCompleted() || status.getPendingPermissionId() != null)
                {
                    return status;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0)
                {
                    return status;
                }
                fieldRecentEvents.wait(Math.min(1000, remaining));
            }
        }
    }

    /**
     * Updates inStatus for a single {@code /api/event}, only if it belongs to inStatus's session.
     * Must be called with events in the order they were received. v2 event payloads live under
     * the {@code data} key (v1 used {@code properties}); heartbeats are SSE comment lines and
     * never arrive as JSON events.
     */
    protected void applySessionEvent(ScriptLogger inLog, SessionStatus inStatus, JSONObject inEvent)
    {
        String type = stringOf(inEvent.get("type"));
        if (type == null)
        {
            return;
        }
        JSONObject data = asObject(inEvent.get("data"));
        if (data == null)
        {
            return;
        }
        if ("permission.asked".equals(type))
        {
            if (inStatus.getSessionId().equals(stringOf(data.get("sessionID"))))
            {
                inStatus.setPendingPermissionId(stringOf(data.get("id")));
                // message is optional in permission.asked; otherwise use what opencode gave: action + resources
                String text = stringOf(data.get("message"));
                if (text == null || text.isEmpty())
                {
                    text = stringOf(data.get("action"));
                    Object resources = data.get("resources");
                    if (resources instanceof JSONArray && !((JSONArray) resources).isEmpty())
                    {
                        text = (text == null ? "" : text + " ") + String.join(", ", (JSONArray) resources);
                    }
                }
                inStatus.setCurrentQuestion(text);
                inStatus.setPendingStatus("securityprompt");
            }
        }
        else if ("form.created".equals(type))
        {
            // Unlike other events, the session id is nested: data.form = {id, sessionID, title, metadata?, fields:[...]}
            JSONObject form = asObject(data.get("form"));
            if (form != null && inStatus.getSessionId().equals(stringOf(form.get("sessionID"))))
            {
                inStatus.setPendingPermissionId(stringOf(form.get("id")));
                inStatus.setCurrentQuestion(stringOf(form.get("title")));
                inStatus.setPendingForm(form);
                inStatus.setPendingStatus("question");
            }
        }
        else if ("permission.replied".equals(type))
        {
            String permissionId = stringOf(data.get("requestID"));
            if (permissionId != null && permissionId.equals(inStatus.getPendingPermissionId()))
            {
                inStatus.setPendingPermissionId(null);
                inStatus.setCurrentQuestion(null);
            }
        }
        else if ("form.replied".equals(type) || "form.cancelled".equals(type))
        {
            // data = {id, sessionID, answer?}; id is the form id
            String formId = stringOf(data.get("id"));
            if (formId != null && formId.equals(inStatus.getPendingPermissionId()))
            {
                inStatus.setPendingPermissionId(null);
                inStatus.setCurrentQuestion(null);
                inStatus.setPendingForm(null);
            }
        }
        else if ("session.execution.failed".equals(type))
        {
            if (inStatus.getSessionId().equals(stringOf(data.get("sessionID"))))
            {
                // data.error is an object: {"type": ..., "message": ...}
                JSONObject error = asObject(data.get("error"));
                String message = error == null ? null : stringOf(error.get("message"));
                inStatus.setError(message != null ? message : stringOf(data.get("error")));
                // The run is over, so any permission still pending can no longer be answered.
                inStatus.setPendingPermissionId(null);
                inStatus.setCurrentQuestion(null);
                inStatus.setCompleted(true);
            }
        }
        else if ("session.execution.interrupted".equals(type))
        {
            if (inStatus.getSessionId().equals(stringOf(data.get("sessionID"))))
            {
                // A cancelled run did not finish the task; report it as an error, not a success.
                inStatus.setError("Session was interrupted before completing");
                inStatus.setPendingPermissionId(null);
                inStatus.setCurrentQuestion(null);
                inStatus.setCompleted(true);
            }
        }
        else if (isExecutionEndFor(inEvent, inStatus.getSessionId()))
        {
            // succeeded ends the turn; failed and interrupted are handled above. The agent can be
            // blocked on a permission when the turn ends, so only mark completed when nothing is
            // pending — after replyToPermission the run resumes and ends again.
            if (inStatus.getPendingPermissionId() == null)
            {
                inStatus.setCompleted(true);
            }
        }
        else
        {
            // Log unhandled event types for debugging purposes
            inLog.debug("OpenCodeClient received unhandled event type: " + type);
        }

    }

    /**
     * Answers a pending permission request (from a {@code permission.asked} event) so the session
     * can continue. inResponse must be one of "once", "always" or "reject". The v2 reply endpoint
     * ({@code POST /api/session/{id}/permission/{requestID}/reply}) answers with 204 and an empty
     * body, so success is judged by the HTTP status.
     */
    public boolean replyToPermission(String inSessionId, String inPermissionId, String inResponse)
    {
        JSONObject body = new JSONObject();
        body.put("decision", inResponse);
        int status = postJsonStatus("/api/session/" + inSessionId + "/permission/" + inPermissionId + "/reply", body);
        return status >= 200 && status < 300;
    }

    /**
     * Answers a pending form (from a {@code form.created} event). inAnswer maps each field key to
     * its value (string, number, boolean or array of strings for multiselect). The v2 endpoint
     * ({@code POST /api/session/{id}/form/{formID}/reply}, body {@code {answer}}) returns 204.
     */
    public boolean replyToForm(String inSessionId, String inFormId, JSONObject inAnswer)
    {
        JSONObject body = new JSONObject();
        body.put("answer", inAnswer);
        int status = postJsonStatus("/api/session/" + inSessionId + "/form/" + inFormId + "/reply", body);
        return status >= 200 && status < 300;
    }

    public SessionStatus loadStatus(String agentJobStepId)
    {
        return sessionMap.get(agentJobStepId);
    }

    protected static long longOf(Object inValue)
    {
        return inValue instanceof Number ? ((Number) inValue).longValue() : 0L;
    }
}
