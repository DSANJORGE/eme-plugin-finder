package org.entermediadb.mcp.client;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.entermediadb.jsonrpc.JsonRpcScanner;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.util.HttpSharedConnection;
import org.openedit.util.JSONParser;

/**
 * Client for a running OpenCode server (https://opencode.ai/docs/server/).
 *
 * <p>Connects to the HTTP API for session and message operations, and maintains a background
 * listener on the {@code /event} SSE stream so callers can wait for server events such as
 * {@code session.idle} after an asynchronous prompt.
 */
public class OpenCodeClient
{
    protected static final String DEFAULT_SERVER_URL = "http://127.0.0.1:4096";
    protected static final long DEFAULT_EVENT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

    // The SSE stream has no heartbeat, so allow long idle periods on the event socket.
    protected static final int SSE_SOCKET_TIMEOUT_MS = 30 * 60 * 1000;

    protected final List<JSONObject> fieldRecentEvents = new ArrayList<>();
    protected String fieldServerUrl = DEFAULT_SERVER_URL;
    protected HttpSharedConnection fieldConnection;
    protected Thread listener;
    protected volatile boolean fieldListening = false;
    protected volatile CloseableHttpResponse fieldEventResponse;

    public String getServerUrl()
    {
        return fieldServerUrl;
    }

    public void setServerUrl(String inServerUrl)
    {
        fieldServerUrl = inServerUrl;
    }

    public HttpSharedConnection getConnection()
    {
        if (fieldConnection == null)
        {
            fieldConnection = new HttpSharedConnection();
            // Must be set before the shared client is first built.
            fieldConnection.setSocketTimeout(SSE_SOCKET_TIMEOUT_MS);
        }
        return fieldConnection;
    }

    /**
     * Checks server health and starts the background event listener.
     */
    public void connectToServer() throws Exception
    {
        JSONObject health = getHealth();
        if (health == null || !Boolean.TRUE.equals(health.get("healthy")))
        {
            throw new Exception("OpenCode server is not healthy at " + getServerUrl());
        }
        startListeningInBackground();
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
     * Blocks reading the {@code /event} SSE stream, dispatching each event to
     * {@link #eventReceived(JSONObject)}. Reconnects with a short delay while
     * {@link #fieldListening} remains true.
     */
    public void startListening()
    {
        while (fieldListening)
        {
            CloseableHttpResponse response = null;
            JsonRpcScanner scanner = null;
            try
            {
                HttpGet method = new HttpGet(getServerUrl() + "/event");
                method.addHeader("Accept", "text/event-stream");
                response = getConnection().sharedExecute(method);
                fieldEventResponse = response;

                int status = response.getStatusLine().getStatusCode();
                if (status < 200 || status >= 300)
                {
                    throw new Exception("Unexpected status from /event: " + status);
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
     * Waits for a {@code session.idle} event matching the given session id. Returns the event, or
     * null on timeout.
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
                    if (!"session.idle".equals(stringOf(event.get("type"))))
                    {
                        continue;
                    }
                    JSONObject properties = asObject(event.get("properties"));
                    if (properties != null && inSessionId.equals(stringOf(properties.get("sessionID"))))
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

    // ---------------------------------------------------------------------
    // Health and sessions
    // ---------------------------------------------------------------------

    public JSONObject getHealth() throws Exception
    {
        CloseableHttpResponse response = null;
        try
        {
            response = getConnection().sharedGet(getServerUrl() + "/global/health");
            return asObject(request(response));
        }
        finally
        {
            getConnection().release(response);
        }
    }

    /**
     * Creates a new session.
     */
    public JSONObject createSession(String inTitle) throws Exception
    {
        JSONObject body = new JSONObject();
        if (inTitle != null)
        {
            body.put("title", inTitle);
        }
        return asObject(postJson("/session", body));
    }

    public Collection<JSONObject> listSessions() throws Exception
    {
        CloseableHttpResponse response = null;
        try
        {
            response = getConnection().sharedGet(getServerUrl() + "/session");
            Object parsed = request(response);
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

    public JSONObject getSession(String inSessionId) throws Exception
    {
        CloseableHttpResponse response = null;
        try
        {
            response = getConnection().sharedGet(getServerUrl() + "/session/" + inSessionId);
            return asObject(request(response));
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
     */
    public JSONObject sendMessage(String inSessionId, String inText) throws Exception
    {
        return asObject(postJson("/session/" + inSessionId + "/message", messageBody(inText)));
    }

    /**
     * Sends a prompt asynchronously. Returns immediately (HTTP 204); wait for completion with
     * {@link #waitForSessionIdle(String, long)}.
     */
    public void sendPromptAsync(String inSessionId, String inText) throws Exception
    {
        postJson("/session/" + inSessionId + "/prompt_async", messageBody(inText));
    }

    /**
     * Aborts any in-flight work on the session.
     */
    public void abortSession(String inSessionId) throws Exception
    {
        postJson("/session/" + inSessionId + "/abort", new JSONObject());
    }

    public List<JSONObject> getMessages(String inSessionId) throws Exception
    {
        CloseableHttpResponse response = null;
        try
        {
            response = getConnection().sharedGet(getServerUrl() + "/session/" + inSessionId + "/message");
            Object parsed = request(response);
            List<JSONObject> messages = new ArrayList<>();
            if (parsed instanceof JSONArray)
            {
                for (Object item : (JSONArray) parsed)
                {
                    JSONObject message = asObject(item);
                    if (message != null)
                    {
                        messages.add(message);
                    }
                }
            }
            return messages;
        }
        finally
        {
            getConnection().release(response);
        }
    }

    protected JSONObject messageBody(String inText)
    {
        JSONObject body = new JSONObject();
        JSONArray parts = new JSONArray();
        JSONObject part = new JSONObject();
        part.put("type", "text");
        part.put("text", inText);
        parts.add(part);
        body.put("parts", parts);
        return body;
    }

    // ---------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------

    protected Object postJson(String inPath, JSONObject inBody) throws Exception
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

    protected Object request(CloseableHttpResponse inResponse) throws Exception
    {
        if (inResponse.getEntity() == null)
        {
            return null; // e.g. 204 No Content from prompt_async/abort
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
}
