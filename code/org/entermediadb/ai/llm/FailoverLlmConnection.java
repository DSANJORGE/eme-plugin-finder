package org.entermediadb.ai.llm;

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
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.OpenEditException;
import org.openedit.data.Searcher;

/**
 * The connection MediaArchive.getLlmConnection hands out for one aiserver type: every enabled
 * record of that type in ordering order, each with its own live connection and circuit breaker.
 * A provider call goes to the first record whose breaker is closed and falls through to the next
 * on any exception or an empty reply; the last failure is rethrown when nobody answers. With a
 * single record it behaves exactly like calling that record directly.
 *
 * Breaker per record: after breakerfailures consecutive failures (default 3) the record is skipped
 * for breakerminutes (default 2), then one call is let through again. State is in memory only.
 *
 * An airoute row whose id is the function name (call template) overrides the order: its aiservers
 * are tried in that order (disabled or unknown ids skipped; a row outside this type's ladder is
 * loaded on demand) and its timeoutseconds, when set, is the socket timeout for every attempt.
 * Every attempt is one aicalllog row (ok, error, timeout, breakeropen). Both need a MediaArchive;
 * without one (unit tests) the ladder alone is used and nothing is logged.
 */
public class FailoverLlmConnection implements LlmConnection
{
	private static Log log = LogFactory.getLog(FailoverLlmConnection.class);

	protected interface Call
	{
		LlmResponse run(LlmConnection inConnection);
	}

	protected static class Entry
	{
		Data server;
		LlmConnection connection; // null when the bean could not be loaded; skipped forever
		int maxFailures;
		long openMs;
		int failures;
		long openUntil;

		Entry(Data inServer, LlmConnection inConnection)
		{
			server = inServer;
			connection = inConnection;
			maxFailures = intValue(inServer, "breakerfailures", 3);
			openMs = intValue(inServer, "breakerminutes", 2) * 60000L;
		}

		String getId()
		{
			return server.getId();
		}

		synchronized boolean isOpen(long inNow)
		{
			// ponytail: once the window passes every waiting thread probes at once instead of exactly one; fine at pilot volume
			return failures >= maxFailures && inNow < openUntil;
		}

		synchronized void succeeded()
		{
			failures = 0;
			openUntil = 0;
		}

		/** @return true when this failure opened (or re-armed) the breaker */
		synchronized boolean failed(long inNow)
		{
			failures++;
			if (failures >= maxFailures)
			{
				openUntil = inNow + openMs;
				return true;
			}
			return false;
		}
	}

	protected static int intValue(Data inServer, String inField, int inDefault)
	{
		// Elasticsearch stores a missing number as 0, so 0 means "use the default"
		try
		{
			int value = Integer.parseInt(String.valueOf(inServer.get(inField)));
			return value > 0 ? value : inDefault;
		}
		catch (NumberFormatException ex)
		{
			return inDefault;
		}
	}

	protected String fieldServerType;
	protected List<Entry> fieldEntries = new ArrayList<Entry>();
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

	public FailoverLlmConnection(String inServerType)
	{
		fieldServerType = inServerType;
	}

	public String getServerType()
	{
		return fieldServerType;
	}

	/** In ordering order. A null connection keeps the record's place but is never called. */
	public void add(Data inServer, LlmConnection inConnection)
	{
		fieldEntries.add(new Entry(inServer, inConnection));
	}

	public int size()
	{
		return fieldEntries.size();
	}

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
			connection = loadConnection(server);
		}
		catch (Exception ex)
		{
			log.warn("llm " + fieldServerType + ": airoute names " + inServerId + " but bean " + server.get("connectionbean") + " failed to load: " + ex.getMessage());
		}
		extra = new Entry(server, connection);
		Entry raced = fieldExtraEntries.putIfAbsent(inServerId, extra);
		return raced == null ? extra : raced;
	}

	/** The connection bean named by a server row outside the ladder. Split out of entryFor so a test can stub it. */
	protected LlmConnection loadConnection(Data inServer)
	{
		LlmConnection connection = (LlmConnection) fieldMediaArchive.getModuleManager().getBean(fieldMediaArchive.getCatalogId(), inServer.get("connectionbean"), false);
		connection.setAiServerData(inServer);
		return connection;
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
					if (entry != null && !Boolean.parseBoolean(entry.server.get("disabled")) && !chain.contains(entry))
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
			int attempt = i + 1; // chain position, not the count of servers actually called: a breakeropen skip still advances this
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
			LlmResponse response = null;
			boolean threw = false;
			try
			{
				if (entry.connection instanceof BaseLlmConnection)
				{
					((BaseLlmConnection) entry.connection).setTimeoutOverride(timeout);
				}
				response = inCall.run(entry.connection);
			}
			catch (Exception ex)
			{
				last = ex;
				threw = true;
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
			// The breaker window is armed from when the attempt actually ended, not when it started: a route
			// timeout close to (or above) breakerminutes would otherwise open a window already in the past.
			long ended = System.currentTimeMillis();
			if (response != null && (response.getRawResponse() != null || response.getRawCollection() != null))
			{
				entry.succeeded();
				if (logsSuccess())
				{
					logAttempt(entry.server, inWhat, "ok", ended - now, attempt, null, response);
				}
				return response;
			}
			if (!threw)
			{
				// An empty reply from this attempt is the reason to report, not a stale exception from an earlier one.
				last = null;
			}
			lastReason = error.split("\n")[0].trim();
			if (lastReason.length() > 120)
			{
				lastReason = lastReason.substring(0, 120) + "...";
			}
			logAttempt(entry.server, inWhat, status, ended - now, attempt, error, null);
			if (entry.failed(ended))
			{
				log.info("llm " + fieldServerType + ": breaker open for " + entry.getId() + " until " + new Date(entry.openUntil));
			}
			Entry next = nextToTry(chain, i + 1, ended);
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

	// ---- aicalllog ----

	/** Embedding/vectorize types run once per entity in bulk imports (10k+ rows); an ok row per call would flood the log. */
	protected boolean logsSuccess()
	{
		return !("embedding".equals(fieldServerType) || "vectorize".equals(fieldServerType));
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

	// ---- provider calls: walk the ladder ----

	public LlmResponse callStructure(AgentContext inContext, String inFunction)
	{
		return failover(inFunction, c -> {
			LlmResponse response = c.callStructure(inContext, inFunction);
			// A salvaged reply carries a payload too, so it passes; prose that is not the schema does not.
			JSONObject payload = response == null ? null : response.getResponsePayload();
			if (payload == null || payload.isEmpty())
			{
				throw new OpenEditException("empty structured payload");
			}
			return response;
		});
	}

	public LlmResponse callSmartCreatorAiAction(AgentContext inContext, String inActionName)
	{
		return failover(inActionName, c -> c.callSmartCreatorAiAction(inContext, inActionName));
	}

	public LlmResponse callCreateFunction(AgentContext inContext, String inFunction)
	{
		return failover(inFunction, c -> c.callCreateFunction(inContext, inFunction));
	}

	public LlmResponse callClassifyFunction(AgentContext inContext, String inFunction, String inBase64Image)
	{
		return failover(inFunction, c -> c.callClassifyFunction(inContext, inFunction, inBase64Image));
	}

	public LlmResponse callClassifyFunction(AgentContext inContext, String inFunction, String inBase64Image, String inTextContent)
	{
		return failover(inFunction, c -> c.callClassifyFunction(inContext, inFunction, inBase64Image, inTextContent));
	}

	public LlmResponse callToolsFunction(AgentContext inContext, String inFunction)
	{
		return failover(inFunction, c -> c.callToolsFunction(inContext, inFunction));
	}

	public LlmResponse callOCRFunction(AgentContext inContext, String inBase64Image, String inFunctionName)
	{
		return failover(inFunctionName, c -> c.callOCRFunction(inContext, inBase64Image, inFunctionName));
	}

	public LlmResponse runPageAsInput(AgentContext inContext, String inChatTemplate)
	{
		return failover(inChatTemplate, c -> c.runPageAsInput(inContext, inChatTemplate));
	}

	public LlmResponse createImage(String inPrompt)
	{
		return failover("createimage", c -> c.createImage(inPrompt));
	}

	public LlmResponse createImage(String inPrompt, int inCount, String inSize)
	{
		return failover("createimage", c -> c.createImage(inPrompt, inCount, inSize));
	}

	public LlmResponse callJson(String inPath, Map inPayload)
	{
		return failover(inPath, c -> c.callJson(inPath, inPayload));
	}

	public LlmResponse callJson(String inPath, JSONObject inPayload)
	{
		return failover(inPath, c -> c.callJson(inPath, inPayload));
	}

	public LlmResponse callJson(String inPath, Map<String, String> inHeaders, Map inMap)
	{
		return failover(inPath, c -> c.callJson(inPath, inHeaders, inMap));
	}

	public LlmResponse callJson(String inPath, Map<String, String> inHeaders, JSONObject inPayload)
	{
		return failover(inPath, c -> c.callJson(inPath, inHeaders, inPayload));
	}

	// ---- local work and plain getters: the record a call would go to right now ----

	public String loadInputFromTemplate(AgentContext inContext, String inTemplate)
	{
		return first().loadInputFromTemplate(inContext, inTemplate);
	}

	public LlmResponse renderLocalAction(AgentContext inContext, String inTemplate)
	{
		return first().renderLocalAction(inContext, inTemplate);
	}

	public LlmResponse createResponse()
	{
		return first().createResponse();
	}

	public String getApiKey()
	{
		return first().getApiKey();
	}

	public String getServerRoot()
	{
		return first().getServerRoot();
	}

	public String getLlmProtocol()
	{
		return first().getLlmProtocol();
	}

	public String getModelName()
	{
		return first().getModelName();
	}

	public Boolean isReady()
	{
		return first().isReady();
	}

	public Data getAiServerData()
	{
		return first().getAiServerData();
	}

	public void setAiServerData(Data inServer)
	{
		first().setAiServerData(inServer);
	}

}
