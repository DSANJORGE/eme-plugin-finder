package org.entermediadb.ai.llm.router;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
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
 * present, else every row not marked disabled of the type by ordering. Fails over on any error, timeout or
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

	// Delegates outlive the router: the 60 s rebuild in MediaArchive.getLlmConnection would otherwise
	// leak one HttpSharedConnection (and its idle-connection evictor thread) per minute per server.
	// Keyed catalogid/aiserverid/connectionbean so a row that switches bean gets a fresh delegate.
	protected static final Map<String, LlmConnection> DELEGATES = new ConcurrentHashMap<String, LlmConnection>();

	public static final int DEFAULT_BREAKER_FAILURES = 3;
	public static final int DEFAULT_BREAKER_MINUTES = 5;
	private static final Pattern HTTP_STATUS = Pattern.compile("\\bHTTP (\\d{3})\\b");

	protected MediaArchive fieldMediaArchive;
	protected String fieldServerType;
	protected List<Data> fieldServers;
	protected DelegateFactory fieldFactory;
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
					if (server != null && !Boolean.parseBoolean(server.get("disabled")))
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
			int seconds = Integer.parseInt(value.trim());
			return seconds <= 0 ? null : Integer.valueOf(seconds);
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
	}

	protected LlmConnection delegate(Data inServer)
	{
		String key = catalogId() + "/" + inServer.getId() + "/" + inServer.get("connectionbean");
		LlmConnection delegate = DELEGATES.get(key);
		if (delegate == null)
		{
			delegate = fieldFactory.create(inServer);
			LlmConnection raced = DELEGATES.putIfAbsent(key, delegate);
			if (raced != null)
			{
				delegate = raced;
			}
		}
		delegate.setAiServerData(inServer); // an edited row (key, model, timeout) takes effect without a new bean
		return delegate;
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
			int parsed = Integer.parseInt(value.trim());
			return parsed <= 0 ? inDefault : parsed;
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
		return BREAKERS.computeIfAbsent(key, k -> new Breaker());
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
		String lasterror = null;
		int attempt = 0;
		for (Data server : chain)
		{
			attempt++;
			Breaker b = breaker(server);
			int failuresAllowed = intValue(server, "breakerfailures", DEFAULT_BREAKER_FAILURES);
			long openMs = intValue(server, "breakerminutes", DEFAULT_BREAKER_MINUTES) * 60000L;
			boolean wasOpen;
			boolean skipBreakerOpen = false;
			synchronized (b)
			{
				wasOpen = b.failures >= failuresAllowed;
				if (wasOpen)
				{
					boolean windowOver = System.currentTimeMillis() - b.openedAt >= openMs;
					if (!windowOver || b.probing)
					{
						skipBreakerOpen = true;
					}
					else
					{
						b.probing = true; // exactly one half-open probe
					}
				}
			}
			if (skipBreakerOpen)
			{
				tried.add(server.getId() + "(breaker open)");
				logAttempt(server, inFunction, "breakeropen", 0, attempt, null, null);
				continue;
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
					lasterror = "blank serverapikey";
					logAttempt(server, inFunction, "misconfigured", 0, attempt, "blank serverapikey", null);
					continue;
				}
			}

			long start = System.currentTimeMillis();
			LlmConnection connection = null;
			try
			{
				connection = delegate(server);
				if (connection instanceof BaseLlmConnection)
				{
					((BaseLlmConnection) connection).setTimeoutOverride(timeoutOverride);
				}
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
			catch (Exception ex)
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
				Integer http = httpStatusOf(message);
				lasterror = message;
				tried.add(server.getId() + "(" + status + (http == null ? "" : " " + http) + " " + ms + "ms)");
				log.error(inFunction + " failed on aiserver " + server.getId() + ": " + message);
				logAttempt(server, inFunction, status, ms, attempt, message, null);
			}
			finally
			{
				// Belt-and-braces: every exit path above already clears these on its own line,
				// but a throw from delegate() itself (factory failure) would skip both without this.
				if (connection instanceof BaseLlmConnection)
				{
					((BaseLlmConnection) connection).setTimeoutOverride(null);
				}
				synchronized (b)
				{
					b.probing = false;
				}
			}
		}
		String problem = "No AI server answered " + inFunction + "; tried " + String.join(", ", tried);
		if (lasterror != null)
		{
			problem = problem + "; last error: " + (lasterror.length() > 500 ? lasterror.substring(0, 500) : lasterror);
		}
		throw new OpenEditException(problem);
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

	/** Pure row-filling logic, split out of logAttempt so it can be unit-tested without a Searcher. */
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
			fillLogRow(row, inServer, inFunction, inStatus, inMs, inAttempt, inError, inResponse);
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
