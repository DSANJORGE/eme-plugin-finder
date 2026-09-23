package org.entermediadb.ai;

import java.util.Collection;
import java.util.Map;
import org.entermediadb.ai.assistant.AiCreation;
import org.entermediadb.ai.assistant.AiSearch;
import org.entermediadb.ai.automation.RunningScenario;
import org.entermediadb.ai.creator.AiSmartCreatorSteps;
import org.entermediadb.ai.knn.RankedResult;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.ai.llm.LogEntry;
import org.entermediadb.scripts.ScriptLogger;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.ModuleManager;
import org.openedit.MultiValued;
import org.openedit.data.SearcherManager;
import org.openedit.profile.UserProfile;
import org.openedit.users.User;

/**
 * Extract every public method from BaseAgentContext and make it available here. This is the context
 * that will be passed to the agent function and should contain everything the agent needs to know
 * about the current state of the conversation and any relevant data.
 * 
 */

public interface AgentContext extends Data
{
	ScriptLogger getScriptLogger();

	void setScriptLogger(ScriptLogger inScriptLogger);

	AgentContext getParentContext();

	void setParentContext(AgentContext inParentContext);

	AgentContext getRootContext();

	Long getWaitTime();

	void setWaitTime(Long inWaitTime);

	RunningScenario getCurrentScenario();

	void setCurrentScenario(RunningScenario inCurrentScenario);

	AutomationStep getCurrentAutomationStep();

	void setCurrentAutomationStep(AutomationStep inCurrentAutomationStep);

	String getCatalogId();

	void setCatalogId(String inCatalogId);

	ModuleManager getModuleManager();

	void setModuleManager(ModuleManager inModuleManager);

	UserProfile getUserProfile();

	void setUserProfile(UserProfile inUserProfile);

	String get(String inKey);

	Map<String, Object> getContext();

	Map<String, Object> getAllContext();

	Data getChannel();

	void setChannel(Data inChannel);

	Object getContextValue(String inKey);

	void setContext(Map<String, Object> inContext);

	void put(String inKey, Object inValue);
	void putAll(Map<String, Object> inMap);
	
	void putRoot(String inKey, Object inValue);

	/**
	 * @deprecated use put()
	 * @param inKey
	 * @param inValue
	 */
	void addContext(String inKey, Object inValue);

	/**
	 * @deprecated use put()
	 * @param inKey
	 * @param inValue
	 */
	void putContextValue(String inKey, Object inValue);

	String toString();

	Collection<RankedResult> getRankedSuggestions();

	void setRankedSuggestions(Collection<RankedResult> inRankedSuggestions);

	AiSearch getAiSearchParams();

	void setAiSearchParams(AiSearch inAiSearchParams);

	AiCreation getAiCreationParams();

	void setAiCreationParams(AiCreation inAiCreationParams);

	AiSmartCreatorSteps getAiSmartCreatorSteps();

	void setAiSmartCreatorSteps(AiSmartCreatorSteps inAiSmartCreatorSteps);

	String getMessagePrefix();

	void setMessagePrefix(String inMessagePrefix);

	Collection<String> getExcludedEntityIds();

	void setExcludedEntityIds(Collection<String> inExcludedEntityIds);

	void addExcludedEntityId(String inExcludedEntityId);

	Collection<String> getExcludedAssetIds();

	void setExcludedAssetIds(Collection<String> inExcludedAssetIds);

	void addExcludedAssetId(String inExcludedAssetId);

	User getChatUser();

	void setLocale(String inLocale);

	String getLocale();

	void log(String inMessage);

	void info(String inMessage);

	void error(String inMessage);

	void error(String inMessage, Throwable inThrowable);

	void setCurrentEntityModule(MultiValued inEntityModule);

	void setCurrentEntity(MultiValued inEntity);

	MultiValued getCurrentEntityModule();

	MultiValued getCurrentEntity();

	Collection<LogEntry> getLogs();

	public LlmResponse getLastResponse();

	public void setLastResponse(LlmResponse inLastResponse);

	public Collection<SkillStatusListener> getStatusListeners();

	public void addStatusListener(SkillStatusListener inListener);

	public void fireStatusStarting(AutomationStep inAutomationStep);

	public void fireStatusComplete(AutomationStep inAutomationStep);

	void putContextValues(Map<String, Object> params);

	JSONObject toJSON();

	String toJSONString();

	void loadContextFromJson(String inJsonString);

	public SearcherManager getSearcherManager();

}
