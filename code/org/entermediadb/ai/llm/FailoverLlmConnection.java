package org.entermediadb.ai.llm;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.OpenEditException;

/**
 * The connection MediaArchive.getLlmConnection hands out for one aiserver type: every enabled
 * record of that type in ordering order, each with its own live connection and circuit breaker.
 * A provider call goes to the first record whose breaker is closed and falls through to the next
 * on any exception or an empty reply; the last failure is rethrown when nobody answers. With a
 * single record it behaves exactly like calling that record directly.
 *
 * Breaker per record: after breakerfailures consecutive failures (default 3) the record is skipped
 * for breakerminutes (default 2), then one call is let through again. State is in memory only.
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

	protected Entry nextUsable(int inFrom, long inNow)
	{
		for (int i = inFrom; i < fieldEntries.size(); i++)
		{
			Entry entry = fieldEntries.get(i);
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
	protected boolean shouldTry(int inIndex, long inNow)
	{
		Entry entry = fieldEntries.get(inIndex);
		return entry.connection != null && (!entry.isOpen(inNow) || nextUsable(inIndex + 1, inNow) == null);
	}

	protected Entry nextToTry(int inFrom, long inNow)
	{
		for (int i = inFrom; i < fieldEntries.size(); i++)
		{
			if (shouldTry(i, inNow))
			{
				return fieldEntries.get(i);
			}
		}
		return null;
	}

	/** The record a plain getter (key, root, model) answers for: the one a call would go to right now. */
	protected LlmConnection first()
	{
		Entry entry = nextUsable(0, System.currentTimeMillis());
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

	protected static String shortReason(Throwable inError)
	{
		String message = inError.getMessage() == null ? inError.getClass().getSimpleName() : inError.getMessage();
		message = message.split("\n")[0].trim();
		return message.length() > 120 ? message.substring(0, 120) + "..." : message;
	}

	protected LlmResponse failover(String inWhat, Call inCall)
	{
		RuntimeException last = null;
		String lastReason = "no usable aiserver";
		for (int i = 0; i < fieldEntries.size(); i++)
		{
			Entry entry = fieldEntries.get(i);
			long now = System.currentTimeMillis();
			if (!shouldTry(i, now))
			{
				continue;
			}
			try
			{
				LlmResponse response = inCall.run(entry.connection);
				if (response != null && (response.getRawResponse() != null || response.getRawCollection() != null))
				{
					entry.succeeded();
					return response;
				}
				lastReason = "empty response";
			}
			catch (RuntimeException ex)
			{
				last = ex;
				lastReason = shortReason(ex);
			}
			if (entry.failed(now))
			{
				log.info("llm " + fieldServerType + ": breaker open for " + entry.getId() + " until " + new Date(entry.openUntil));
			}
			Entry next = nextToTry(i + 1, now);
			if (next != null)
			{
				log.info("llm " + fieldServerType + ": " + entry.getId() + " failed (" + lastReason + ") -> trying " + next.getId());
			}
		}
		log.error("llm " + fieldServerType + ": all providers failed for " + inWhat + " (" + lastReason + ")");
		if (last != null)
		{
			throw last;
		}
		throw new OpenEditException("llm " + fieldServerType + ": all providers failed for " + inWhat + " (" + lastReason + ")");
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
