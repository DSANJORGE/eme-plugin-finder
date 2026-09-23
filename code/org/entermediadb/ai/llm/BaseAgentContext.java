package org.entermediadb.ai.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.SkillStatusListener;
import org.entermediadb.ai.assistant.AiCreation;
import org.entermediadb.ai.assistant.AiSearch;
import org.entermediadb.ai.automation.RunningScenario;
import org.entermediadb.ai.creator.AiSmartCreatorSteps;
import org.entermediadb.ai.knn.RankedResult;
import org.entermediadb.scripts.ScriptLogger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.CatalogEnabled;
import org.openedit.Data;
import org.openedit.ModuleManager;
import org.openedit.MultiValued;
import org.openedit.data.BaseData;
import org.openedit.data.SearcherManager;
import org.openedit.profile.UserProfile;
import org.openedit.users.User;
import org.openedit.util.JSONParser;

public class BaseAgentContext extends BaseData implements CatalogEnabled, AgentContext
{
	protected ScriptLogger fieldScriptLogger;

	Collection<SkillStatusListener> fieldStatusListeners;

	protected SearcherManager fieldSearcherManager;

	public SearcherManager getSearcherManager()
	{
		if (fieldSearcherManager == null && getParentContext() != null)
		{
			return getParentContext().getSearcherManager();
		}
		if (fieldSearcherManager == null)
		{
			fieldSearcherManager = (SearcherManager) getModuleManager().getBean("searcherManager");
		}
		return fieldSearcherManager;
	}

	public void setSearcherManager(SearcherManager inSearcherManager)
	{
		fieldSearcherManager = inSearcherManager;
	}

	public Collection<SkillStatusListener> getStatusListeners()
	{
		if (getParentContext() != null)
		{
			return getParentContext().getStatusListeners();
		}

		if (fieldStatusListeners == null)
		{
			fieldStatusListeners = new HashSet();
		}
		return fieldStatusListeners;
	}

	public void setStatusListeners(Collection<SkillStatusListener> inStatusListeners)
	{
		fieldStatusListeners = inStatusListeners;
	}

	public void addStatusListener(SkillStatusListener inListener)
	{
		getStatusListeners().add(inListener);
	}

	public void fireStatusStarting(AutomationStep inAutomationStep)
	{
		for (SkillStatusListener listener : getStatusListeners())
		{
			listener.handleStatusStarting(this, inAutomationStep);
		}
	}

	@Override
	public void fireStatusComplete(AutomationStep inAutomationStep)
	{
		for (SkillStatusListener listener : getStatusListeners())
		{
			listener.handleStatusComplete(this, inAutomationStep);
		}
	}

	public BaseAgentContext() {

	}

	public BaseAgentContext(AgentContext inParent) {
		setParentContext(inParent);
	}

	public ScriptLogger getScriptLogger()
	{
		if (fieldScriptLogger == null)
		{
			if (getParentContext() != null)
			{
				return getParentContext().getScriptLogger();
			}
			if (getParentContext() == null)
			{
				fieldScriptLogger = new ScriptLogger();
			}
		}
		return fieldScriptLogger;
	}

	public void setScriptLogger(ScriptLogger inScriptLogger)
	{
		fieldScriptLogger = inScriptLogger;
	}

	protected AgentContext fieldParentContext;

	public AgentContext getParentContext()
	{
		return fieldParentContext;
	}

	public void setParentContext(AgentContext inParentContext)
	{
		fieldParentContext = inParentContext;
	}

	public AgentContext getRootContext()
	{
		if (getParentContext() != null)
		{
			return getParentContext().getRootContext();
		}
		return this;
	}

	protected String functionName;
	protected String nextFunctionName;
	protected Map<String, Object> context;
	// JSONObject arguments;

	protected Long fieldWaitTime;

	// TODO: Cache history here for performance

	public Long getWaitTime()
	{
		return fieldWaitTime;
	}

	public void setWaitTime(Long inWaitTime)
	{
		fieldWaitTime = inWaitTime;
	}

	protected UserProfile fieldUserProfile;

	protected ModuleManager fieldModuleManager;

	protected String fieldCatalogId;

	public RunningScenario getCurrentScenario()
	{
		RunningScenario scenario = (RunningScenario) getRootContext().getContextValue("currentscenario");
		return scenario;
	}

	public void setCurrentScenario(RunningScenario inCurrentScenario)
	{
		//fieldCurrentScenario = inCurrentScenario;
		putRoot("currentscenario", inCurrentScenario);

		if (inCurrentScenario != null)
		{
			setValue("currentscenario", inCurrentScenario.getId());
		}
		else
		{
			setValue("currentscenario", null);
		}
	}

	protected AutomationStep fieldCurrentAutomationStep;

	public AutomationStep getCurrentAutomationStep()
	{
		if (fieldCurrentAutomationStep == null && getParentContext() != null)
		{
			return getParentContext().getCurrentAutomationStep();
		}
		if( fieldCurrentAutomationStep == null && getCurrentScenario() != null)
		{
			Collection<AutomationStep> enabled = getCurrentScenario().getEnabledAgents();
			if( enabled != null && enabled.size() > 0)
			{
				fieldCurrentAutomationStep = enabled.iterator().next();
			}
		}
		return fieldCurrentAutomationStep;
	}

	public void setCurrentAutomationStep(AutomationStep inCurrentAutomationStep)
	{
		fieldCurrentAutomationStep = inCurrentAutomationStep;
		if (inCurrentAutomationStep != null)
		{
			if (inCurrentAutomationStep.getExtraContextValues() != null)
			{
				JSONObject json = inCurrentAutomationStep.getExtraContextValues();
				for (Object key : json.keySet())
				{
					Object value = json.get(key);
					addContext(String.valueOf(key), value);
				}
			}
		}
	}

	public String getCatalogId()
	{
		if (fieldCatalogId == null && getParentContext() != null)
		{
			return getParentContext().getCatalogId();
		}
		return fieldCatalogId;
	}

	public void setCatalogId(String inCatalogId)
	{
		fieldCatalogId = inCatalogId;
	}

	public ModuleManager getModuleManager()
	{
		if (fieldModuleManager == null && getParentContext() != null)
		{
			return getParentContext().getModuleManager();
		}
		return fieldModuleManager;
	}

	public void setModuleManager(ModuleManager inModuleManager)
	{
		fieldModuleManager = inModuleManager;
	}

	public UserProfile getUserProfile()
	{
		if (fieldUserProfile == null && getParentContext() != null)
		{
			return getParentContext().getUserProfile();
		}
		return fieldUserProfile;
	}

	public void setUserProfile(UserProfile inUserProfile)
	{
		fieldUserProfile = inUserProfile;
	}

	@Override
	public String get(String inId)
	{
		String value = super.get(inId);

		// Is this really needed? It seems like it would be better to just use the context values instead of
		if (value == null && inId.equals("entityid"))
		{
			if (!inId.equals("channel") && getChannel() != null)
			{
				value = getChannel().get("dataid");
			}
		}
		else if (value == null && inId.equals("entitymoduleid"))
		{
			if (!inId.equals("channel") && getChannel() != null)
			{
				value = getChannel().get("searchtype");
			}
		}
		if (value == null && getParentContext() != null)
		{
			return getParentContext().get(inId);
		}
		return value;
	}

	public Object getValue(String inId)
	{
		Object value = super.getValue(inId);

		if (value == null && getParentContext() != null)
		{
			return getParentContext().getValue(inId);
		}
		return value;
	}

	public Map<String, Object> getContext()
	{
		if (context == null)
		{
			context = new HashMap();

		}
		return context;
	}

	public Map<String, Object> getAllContext()
	{
		Map<String, Object> fullcontext = new HashMap();
		if (getParentContext() != null)
		{
			fullcontext.putAll(getParentContext().getAllContext());
		}
		fullcontext.putAll(getContext());
		return fullcontext;
	}

	public Data getChannel()
	{
		Data channel = (Data) getContextValue("channel");
		if (channel == null)
		{

		}
		return channel;
	}

	public void setChannel(Data inChannel)
	{
		setValue("channel", inChannel.getId());
		addContext("channel", inChannel);
	}

	public Object getContextValue(String inKey)
	{
		Object obj = null;
		if (getParentContext() == null)
		{
			obj = getContext().get(inKey);
			return obj;
		}
		obj = getRootContext().getContextValue(inKey);
		if (obj == null)
		{
			obj = getContext().get(inKey);
		}
		return obj;
	}

	public void setContext(Map<String, Object> inContext)
	{
		context = inContext;
	}

	/**
	 * @param inKey
	 * @param inValue
	 */
	public void putContextValue(String inKey, Object inValue)
	{
		put(inKey, inValue);
	}

	public void putAl(Map<String,Object> inMap)
	{
		for (String key : inMap.keySet()) 
		{
			Object value = inMap.get(key);
			put(key, value);
		}
	}
	/**
	 * @deprecated use put instead.
	 * @param inKey
	 * @param inValue
	 */
	public void addContext(String inKey, Object inValue)
	{
		put(inKey, inValue);
	}

	public void putRoot(String inKey, Object inValue)
	{
		getRootContext().getContext().put(inKey, inValue);
	}

	public void put(String inKey, Object inValue)
	{
		getContext().put(inKey, inValue); // Track what is changed locally..
		AgentContext ctx = getParentContext();
		while (ctx != null)
		{
			ctx.getContext().remove(inKey);
			ctx = ctx.getParentContext();
		}
		if ("selectedoption".equals(inKey))
		{
			String option = String.valueOf(inValue);
		}
		getRootContext().getContext().put(inKey, inValue); // Make sure everyone is updated
	}

	/**	(non-Javadoc)
	 * @deprecated use put
	 * @see org.entermediadb.ai.AgentContext#putContextValues(java.util.Map)
	 */
	public void putContextValues(Map<String, Object> inValues)
	{
		putAll(inValues);
	}

	public void putAll(Map<String, Object> inMap)
	{
		for (String key : inMap.keySet())
		{
			Object value = inMap.get(key);
			put(key, value);
		}
	}
	

	// public JSONObject getArguments() {
	// return arguments;
	// }
	//
	// public void setArguments(JSONObject inArguments) {
	// arguments = inArguments;
	// }

	public String toString()
	{
		JSONObject obj = new JSONObject();
		obj.put("function", functionName);
		obj.put("nextfunction", nextFunctionName);
		return obj.toJSONString();
	}

	protected String fieldFunctionName;
	protected AiSearch fieldAiSearchParams;

	Collection<RankedResult> fieldRankedSuggestions;

	public Collection<RankedResult> getRankedSuggestions()
	{
		if (fieldRankedSuggestions == null && getParentContext() != null)
		{
			return getParentContext().getRankedSuggestions();
		}
		return fieldRankedSuggestions;
	}

	public void setRankedSuggestions(Collection<RankedResult> inRankedSuggestions)
	{
		fieldRankedSuggestions = inRankedSuggestions;
	}

	public AiSearch getAiSearchParams()
	{
		if (fieldAiSearchParams == null && getParentContext() != null)
		{
			return getParentContext().getAiSearchParams();
		}
		return fieldAiSearchParams;
	}

	public void setAiSearchParams(AiSearch inAiSearchParams)
	{
		fieldAiSearchParams = inAiSearchParams;
	}

	AiCreation fieldAiCreationParams;

	public AiCreation getAiCreationParams()
	{
		if (fieldAiCreationParams == null && getParentContext() != null)
		{
			return getParentContext().getAiCreationParams();
		}
		if (fieldAiCreationParams == null)
		{
			fieldAiCreationParams = new AiCreation();
		}
		return fieldAiCreationParams;
	}

	public void setAiCreationParams(AiCreation inAiCreationParams)
	{
		fieldAiCreationParams = inAiCreationParams;
	}

	public AiSmartCreatorSteps getAiSmartCreatorSteps()
	{
		if (getParentContext() != null)
		{
			return (AiSmartCreatorSteps) getParentContext().getAiSmartCreatorSteps();
		}
		AiSmartCreatorSteps steps = (AiSmartCreatorSteps) getContextValue("aicreationparams");
		return steps;
	}

	public void setAiSmartCreatorSteps(AiSmartCreatorSteps inAiCreatorSteps)
	{
		putContextValue("aicreationparams", inAiCreatorSteps);
	}

	public String getMessagePrefix()
	{
		String message = get("messageprefix");
		if (message == null)
		{
			message = "";
		}
		return message;
	}

	public void setMessagePrefix(String inMessagePrefix)
	{
		setValue("messageprefix", inMessagePrefix);
	}

	Collection<String> fieldExcludedEntityIds;
	Collection<String> fieldExcludedAssetIds;

	public Collection<String> getExcludedEntityIds()
	{
		if (fieldExcludedEntityIds == null && getParentContext() != null)
		{
			return getParentContext().getExcludedEntityIds();
		}
		return fieldExcludedEntityIds;
	}

	public void setExcludedEntityIds(Collection<String> inExcludedEntityids)
	{
		fieldExcludedEntityIds = inExcludedEntityids;
	}

	public void addExcludedEntityId(String inEntityid)
	{
		if (fieldExcludedEntityIds == null)
		{
			fieldExcludedEntityIds = new java.util.ArrayList<>();
		}
		fieldExcludedEntityIds.add(inEntityid);
	}

	public Collection<String> getExcludedAssetIds()
	{
		return fieldExcludedAssetIds;
	}

	public void setExcludedAssetIds(Collection<String> inExcludedAssetids)
	{
		fieldExcludedAssetIds = inExcludedAssetids;
	}

	public void addExcludedAssetId(String inAssetid)
	{
		if (fieldExcludedAssetIds == null)
		{
			fieldExcludedAssetIds = new java.util.ArrayList<>();
		}
		fieldExcludedAssetIds.add(inAssetid);
	}

	public User getChatUser()
	{
		if (getUserProfile() == null)
		{
			return null;
		}
		return getUserProfile().getUser();
	}

	public void setLocale(String inLocale)
	{
		setValue("locale", inLocale);
	}

	public String getLocale()
	{
		return get("locale");
	}

	/* Log to console only */
	public void log(String inLog)
	{
		getScriptLogger().info("[" + getCatalogId() + "] " + inLog);
	}

	public void info(String inLog)
	{
		getScriptLogger().info(inLog);
		addEntry("info", inLog);
	}

	public Date getLastActive()
	{
		if (getLogs().size() > 0)
		{
			return getLogs().iterator().next().getDate();
		}
		return null;
	}

	protected void addEntry(String inString, String inLog)
	{
		LogEntry entry = new LogEntry(inString, inLog);
		entry.setDate(new Date());
		if (getCurrentAutomationStep() != null)
		{
			entry.setCurrentAutomationStepData(getCurrentAutomationStep().getAutomationStepData());
			entry.setAgentData(getCurrentAutomationStep().getAgentData());
		}
		getLogs().add(entry);
	}

	public void error(Exception inE)
	{
		getScriptLogger().error(inE);
		addEntry("error", inE.getMessage());
	}

	public void error(String inString, Throwable inE)
	{
		getScriptLogger().error(inString, inE);
		addEntry("error", inString + " " + inE.getMessage());
	}

	public void headline(String string)
	{
		getScriptLogger().headline(string);
		addEntry("headline", string);
	}

	public void error(String string)
	{
		getScriptLogger().error(string);
		addEntry("error", string);
	}

	public MultiValued getCurrentEntity()
	{
		MultiValued entity = (MultiValued) getContextValue("currententity");
		return entity;
	}

	public MultiValued getCurrentEntityModule()
	{
		return (MultiValued) getContextValue("currententitymodule");
	}

	public void setCurrentEntity(MultiValued inEntity)
	{
		put("currententity", inEntity);
	}

	public void setCurrentEntityModule(MultiValued inEntityModule)
	{
		put("currententitymodule", inEntityModule);
	}

	protected Collection<LogEntry> fieldLogs;

	public Collection<LogEntry> getLogs()
	{
		if (fieldLogs == null && getParentContext() != null)
		{
			return getParentContext().getLogs();
		}
		if (fieldLogs == null)
		{
			fieldLogs = new ArrayList(); // Just on the top parent
		}
		return fieldLogs;
	}

	public void setLogs(Collection<LogEntry> inLogs)
	{
		fieldLogs = inLogs;
	}

	public int getTotalErrorLogs()
	{
		int count = 0;
		for (Iterator<LogEntry> iterator = getLogs().iterator(); iterator.hasNext();)
		{
			LogEntry logEntry = (LogEntry) iterator.next();
			if ("error".equals(logEntry.getLogType()))
			{
				count++;
			}
		}

		return count;
	}

	// Dont keep this shared across contexts. It should be set by the skill that is running it and only
	// used for the next skill to determine what to run next. After that it should be cleared.

	public LlmResponse getLastResponse()
	{
		LlmResponse lastresponse = (LlmResponse) getContextValue("lastresponse");
		return lastresponse;
	}

	public void setLastResponse(LlmResponse inLastResponse)
	{
		putRoot("lastresponse", inLastResponse);
	}

	public JSONObject toJSON()
	{
		Map<String, Object> context = getContext();

		JSONObject out = new JSONObject();

		for (String key : context.keySet())
		{
			Object inValue = getJSONCompatibleObject(context.get(key));
			if (inValue != null)
			{
				out.put(key, inValue);
			}
		}
		return out;
	}

	public String toJSONString()
	{
		return toJSON().toJSONString();
	}

	protected Object getJSONCompatibleObject(Object inValue)
	{
		if (inValue instanceof Data)
		{

			Data d = (Data) inValue;
			String sourcetype = d.get("entitysourcetype");
			if (sourcetype == null)
			{
				return null;
			}

			Map data = new HashMap<>();
			data.put("id", d.getId());
			data.put("searchtype", sourcetype);
			return data;
		}
		if (inValue instanceof String || inValue instanceof Number || inValue instanceof Boolean)
		{
			return inValue;
		}
		if (inValue instanceof Collection)
		{
			JSONArray jsonArray = new JSONArray();
			for (Object item : (Collection) inValue)
			{
				Object data = getJSONCompatibleObject(item);
				if (data != null)
				{
					jsonArray.add(data);
				}
			}
			return jsonArray;
		}
		if (inValue instanceof Map)
		{
			JSONObject jsonObject = new JSONObject();
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) inValue).entrySet())
			{
				Object data = getJSONCompatibleObject(entry.getValue());
				if (data != null)
				{
					jsonObject.put(String.valueOf(entry.getKey()), data);
				}
			}
			return jsonObject;
		}
		return null;
	}

	@Override
	public void loadContextFromJson(String inJsonString)
	{
		if (inJsonString == null || inJsonString.trim().isEmpty())
		{
			return;
		}
		try
		{
			JSONObject json = (JSONObject) new JSONParser().parse(inJsonString);
			if (json != null)
			{
				for (Object keyObj : json.keySet())
				{
					String key = String.valueOf(keyObj);
					Object hydrated = fromJSONCompatibleObject(json.get(keyObj));
					if (hydrated != null)
					{
						putContextValue(key, hydrated);
					}
				}
			}
		}
		catch (Exception ex)
		{
			getScriptLogger().error("Could not parse context json: " + inJsonString, ex);
		}
	}

	protected Object fromJSONCompatibleObject(Object inValue)
	{
		if (inValue == null)
		{
			return null;
		}
		if (inValue instanceof Map)
		{
			Map<?, ?> map = (Map<?, ?>) inValue;
			Object idObj = map.get("id");
			Object searchTypeObj = map.get("searchtype");

			if (idObj instanceof String && searchTypeObj instanceof String && map.size() == 2)
			{
				if (getSearcherManager() != null && getCatalogId() != null)
				{
					Data data = (Data) getSearcherManager().getCachedData(getCatalogId(), (String) searchTypeObj, (String) idObj);
					if (data != null)
					{
						return data;
					}
				}
			}

			Map<String, Object> hydratedMap = new HashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet())
			{
				Object val = fromJSONCompatibleObject(entry.getValue());
				if (val != null)
				{
					hydratedMap.put(String.valueOf(entry.getKey()), val);
				}
			}
			return hydratedMap;
		}
		if (inValue instanceof Collection)
		{
			List<Object> list = new ArrayList<>();
			for (Object item : (Collection<?>) inValue)
			{
				Object hydrated = fromJSONCompatibleObject(item);
				if (hydrated != null)
				{
					list.add(hydrated);
				}
			}
			return list;
		}
		if (inValue instanceof String || inValue instanceof Number || inValue instanceof Boolean)
		{
			return inValue;
		}
		return inValue;
	}

}
