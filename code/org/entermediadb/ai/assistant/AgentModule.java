package org.entermediadb.ai.assistant;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.automation.RunningScenario;
import org.entermediadb.ai.informatics.InformaticsProcessorManager;
import org.entermediadb.ai.skills.MediaCreationSkill;
import org.entermediadb.ai.skills.QuestionsSkill;
import org.entermediadb.ai.skills.SearchingSkill;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.asset.modules.BaseMediaModule;
import org.entermediadb.scripts.ScriptLogger;
import org.entermediadb.workspace.WorkspaceManager;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;

public class AgentModule extends BaseMediaModule
{
	private static final Log log = LogFactory.getLog(AgentModule.class);

	public AssistantManager getAssistantManager(WebPageRequest inReq)
	{
		String catalogid = inReq.findValue("catalogid");
		AssistantManager assistantManager = (AssistantManager) getMediaArchive(catalogid).getBean("assistantManager");
		return assistantManager;
	}

	public MediaCreationSkill getCreationSkill(WebPageRequest inReq)
	{
		String catalogid = inReq.findValue("catalogid");
		MediaCreationSkill creationManager = (MediaCreationSkill) getMediaArchive(catalogid).getBean("creationSkill");
		return creationManager;
	}

	public QuestionsSkill getQuestionsSkill(WebPageRequest inReq)
	{
		String catalogid = inReq.findValue("catalogid");
		QuestionsSkill questionsManager = (QuestionsSkill) getMediaArchive(catalogid).getBean("questionsSkill");
		return questionsManager;
	}

	public SearchingSkill getSearchingSkill(WebPageRequest inReq)
	{
		String catalogid = inReq.findValue("catalogid");
		SearchingSkill searchingManager = (SearchingSkill) getMediaArchive(catalogid).getBean("searchingSkill");
		return searchingManager;
	}

	public void searchTables(WebPageRequest inReq) throws Exception
	{
		AgentContext agentContext = (AgentContext) inReq.getPageValue("agentcontext");

		getSearchingSkill(inReq).searchTables(inReq, agentContext.getAiSearchParams());
	}

	public void chatAgentSemanticSearch(WebPageRequest inReq) throws Exception
	{
		AgentContext agentContext = (AgentContext) inReq.getPageValue("agentcontext");

		String semanticquery = agentContext.get("semanticquery");

		agentContext.setValue("semanticquery", null);
		// agentContext.setRunFunctionName(null);

		inReq.setRequestParameter("semanticquery", semanticquery);
		if (agentContext.getExcludedEntityIds() != null)
		{
			String[] excluded = agentContext.getExcludedEntityIds().toArray(new String[0]);
			inReq.setRequestParameter("excludeentityids", excluded);
		}
		if (agentContext.getExcludedAssetIds() != null)
		{
			String[] excluded = agentContext.getExcludedAssetIds().toArray(new String[0]);
			inReq.setRequestParameter("excludeassetids", excluded);
		}

		if (semanticquery == null)
		{
			return;
		}

		String query = (String) semanticquery;

		if (query != null && !"null".equals(query))
		{
			getSearchingSkill(inReq).semanticSearch(inReq);
		}
	}

	// public void mcpSearch(WebPageRequest inReq) throws Exception
	// {
	// getAssistantManager(inReq).regularSearch(inReq, true);
	// }

	public void loadSemanticMatches(WebPageRequest inReq) throws Exception
	{
		String query = inReq.getRequestParameter("semanticquery");

		if (query != null && !"null".equals(query))
		{
			getSearchingSkill(inReq).semanticSearch(inReq);
		}
	}

	public void recreateFunctions(WebPageRequest inReq) throws Exception
	{
		ScriptLogger log = (ScriptLogger) inReq.getPageValue("log");
		AssistantManager assistant = (AssistantManager) getMediaArchive(inReq).getBean("assistantManager");
		assistant.addSmartCreatorTypes(log);

		SearchingSkill searchingManager = getSearchingSkill(inReq);
		searchingManager.createPossibleFunctionParameters(log);

		// Add AI functions to mediadb
		WorkspaceManager workspaceManager = (WorkspaceManager) getMediaArchive(inReq).getBean("workspaceManager");
		workspaceManager.createMediaDbAiFunctionEndPoints(getMediaArchive(inReq).getCatalogId());

	}

	public void loadModuleSchemaForJson(WebPageRequest inReq) throws Exception
	{
		AssistantManager assistant = (AssistantManager) getMediaArchive(inReq).getBean("assistantManager");
		Collection<String> modulesenum = assistant.getModulesAsEnum();
		inReq.putPageValue("modulesenum", modulesenum);
	}

	public void loadSearchSuggestions(WebPageRequest inReq) throws Exception
	{
		SearchingSkill searching = (SearchingSkill) getMediaArchive(inReq).getBean("searchingSkill");
		Collection<String> suggestions = searching.makeSearchSuggestions(inReq.getUserProfile());
		inReq.putPageValue("suggestions", suggestions);
	}

	public void saveAgentContextField(WebPageRequest inReq) throws Exception
	{
		AgentContext agentContext = (AgentContext) inReq.getPageValue("agentcontext");
		String fieldname = inReq.getRequestParameter("fieldname");
		String fieldvalue = inReq.getRequestParameter("fieldvalue");
		agentContext.setValue(fieldname, fieldvalue);
		Searcher searcher = getMediaArchive(inReq).getSearcher("agentcontext");
		searcher.saveData(agentContext, inReq.getUser());
	}

	public void loadTutorials(WebPageRequest inReq) throws Exception
	{
		Searcher tutorialsearcher = getMediaArchive(inReq).getSearcher("aitutorial");
		HitTracker hits = tutorialsearcher.query().exact("featured", true).search();

		inReq.putPageValue("tutorials", hits);
	}

	public void sendWelcomeIfNeeded(WebPageRequest inReq) throws Exception
	{
		MediaArchive mediaArchive = getMediaArchive(inReq);

		AssistantManager assistantManager = (AssistantManager) mediaArchive.getBean("assistantManager");

		// Get the contenxt and update it first
		String channelid = inReq.getRequestParameter("channel");
		if (channelid == null)
		{
			// log.error("Channel is required to send welcome message");
			return;
		}
		Data channel = mediaArchive.getCachedData("channel", channelid);
		String applicationid = inReq.findValue("applicationid");
		ChatMessageContext chatAgentContext = assistantManager.loadChatContext(applicationid, channel);
		if (chatAgentContext == null)
		{
			// log.error("Chat context is required to send welcome message");
			return;
		}
		chatAgentContext.setLocale(inReq.getLocale());
		// Refresh drop down area?
		inReq.putPageValue("agentcontext", chatAgentContext);

		boolean firesystemmessage = false;

		String functionname = inReq.getRequestParameter("functionname");

		String currentscenarioid = inReq.getRequestParameter("currentscenario");
		if (currentscenarioid == null)
		{
			if (chatAgentContext.getCurrentScenario() != null)
			{
				currentscenarioid = chatAgentContext.getCurrentScenario().getId();
			}
			else
			{
				currentscenarioid = inReq.findValue("defaultscenarioid");
				if (currentscenarioid == null)
				{
					currentscenarioid = "chat_detection";
				}
			}
		}

		// See if change of scenario
		if (chatAgentContext.getCurrentScenario() != null && !currentscenarioid.equals(chatAgentContext.getCurrentScenario().getId()))
		{
			chatAgentContext.setCurrentScenario(null);
		}
		if (chatAgentContext.getCurrentScenario() == null)
		{
			// Scenario changed. Clear the context and start over.
			RunningScenario running = (RunningScenario) mediaArchive.getBean("runningscenario", false);
			running.setId(currentscenarioid);
			chatAgentContext.setCurrentScenario(running);
			mediaArchive.saveData("agentcontext", chatAgentContext);

			firesystemmessage = true;

			if (functionname == null)
			{
				functionname = chatAgentContext.getCurrentScenario().getId() + "_welcome";
			}
		}
		else if (functionname != null)
		{
			firesystemmessage = true;
		}

		if (!firesystemmessage)
		{

			return;
		}

		MultiValued agentMessage = chatAgentContext.getAgentMessage();
		if (agentMessage == null)
		{
			agentMessage = (MultiValued) mediaArchive.getSearcher("chatterbox").createNewData();
			chatAgentContext.setAgentMessage(agentMessage);
		}

		Collection<String> params = inReq.getParameterMap().keySet();
		// boolean context
		for (Iterator iterator = params.iterator(); iterator.hasNext();)
		{
			String key = (String) iterator.next();
			if (key.startsWith("context_"))
			{
				String value = inReq.getRequestParameter(key);
				if (value != null)
				{
					String keyName = key.substring("context_".length());
					chatAgentContext.putContextValue(keyName, value);
				}
			}
		}

		mediaArchive.saveData("agentcontext", chatAgentContext);
		// assistantManager.sendSystemMessage(chatAgentContext, inReq.getUserName(), functionname);
		assistantManager.sendSystemMessage(chatAgentContext, inReq.getUserName(), null, functionname);
	}

	public AgentContext loadAgentContext(WebPageRequest inReq) throws Exception
	{
		AssistantManager assistantManager = (AssistantManager) getMediaArchive(inReq).getBean("assistantManager");

		// Get the contenxt and update it first
		Data currentchannel = (Data) inReq.getPageValue("currentchannel");
		if (currentchannel == null)
		{
			throw new Exception("Channel is required to load context");
		}

		String applicationid = inReq.findValue("applicationid");

		AgentContext context = assistantManager.loadChatContext(applicationid, currentchannel);

		context.setLocale(inReq.getLocale());

		inReq.putPageValue("agentcontext", context);

		inReq.setRequestParameter("channel", currentchannel.getId());
		// inReq.setRequestParameter("toplevelaifunctionid", "auto_detect_welcome");
		// inReq.setRequestParameter("functionname", "auto_detect_welcome");

		// if( toplevel != null )
		// {
		// context.setTopLevelFunctionName(toplevel);
		// }
		// String functionname = inReq.getRequestParameter("functionname");
		// if( functionname != null )
		// {
		// context.setFunctionName(functionname);
		// }
		// if( toplevel != null ||functionname != null )
		// {
		// getMediaArchive(inReq).saveData("agentcontext",context);
		// }
		return context;

		// Now that Context is set. Let the chat respond
		// Refresh drop down area?
	}

	public void monitorChannels(WebPageRequest inReq) throws Exception
	{
		MediaArchive archive = getMediaArchive(inReq);
		AssistantManager assistantManager = (AssistantManager) archive.getBean("assistantManager");
		ScriptLogger log = (ScriptLogger) inReq.getPageValue("log");
		assistantManager.monitorChannels(log);
	}

	public void verifyRevisions(WebPageRequest inReq)
	{
		Data data = (Data) inReq.getPageValue("data");

	}

	public void monitorAiServers(WebPageRequest inReq) throws Exception
	{
		MediaArchive archive = getMediaArchive(inReq);
		AssistantManager assistantManager = (AssistantManager) archive.getBean("assistantManager");
		ScriptLogger log = (ScriptLogger) inReq.getPageValue("log");
		assistantManager.monitorAiServers(log);
	}

	public void resetInformatics(WebPageRequest inReq) throws Exception
	{
		MediaArchive archive = getMediaArchive(inReq);
		InformaticsProcessorManager manager = (InformaticsProcessorManager) archive.getBean("informaticsProcessorManager");
		ScriptLogger log = (ScriptLogger) inReq.getPageValue("log");

		String moduleid = inReq.findValue("module");

		String hitsessionid = inReq.getRequestParameter("hitssessionid");
		HitTracker hitsession = (HitTracker) inReq.getSessionValue(hitsessionid);
		manager.resetInformatics(moduleid, hitsession.getSelectedHitracker());

	}

	public void loadRelatedRecords(WebPageRequest inReq) throws Exception
	{
		String entityid = inReq.findValue("entityid");
		String entitymoduleid = inReq.findValue("entitymoduleid");

		Collection<Map> related = getSearchingSkill(inReq).getRelatedRecords(entitymoduleid, entityid);

		inReq.putPageValue("relatedrecords", related);
	}

	public void loadRelatedRecordList(WebPageRequest inReq) throws Exception
	{
		String entityid = inReq.findValue("entityid");
		String entitymoduleid = inReq.findValue("entitymoduleid");
		String listid = inReq.getRequestParameter("relatedmoduleid");

		Data recordmodule = getMediaArchive(inReq).getCachedData("module", listid);
		inReq.putPageValue("recordmodule", recordmodule);

		Collection<Data> recordlist = getSearchingSkill(inReq).getRelatedRecordList(entitymoduleid, entityid, listid);

		inReq.putPageValue("recordlist", recordlist);
	}

	public void loadRecord(WebPageRequest inReq) throws Exception
	{
		String entityid = inReq.findValue("entityid");
		String entitymoduleid = inReq.findValue("entitymoduleid");
		String listid = inReq.getRequestParameter("relatedmoduleid");
		String recordid = inReq.getRequestParameter("recordid");

		Data recordmodule = getMediaArchive(inReq).getCachedData("module", listid);
		inReq.putPageValue("recordmodule", recordmodule);

		Data record = getSearchingSkill(inReq).getRecord(entitymoduleid, entityid, listid, recordid);

		inReq.putPageValue("record", record);
	}

	// public void startInformatics(WebPageRequest inReq) throws Exception
	// {
	// MediaArchive archive = getMediaArchive(inReq);
	// InformaticsProcessorAgent informaticsManager =
	// (InformaticsProcessorAgent)archive.getBean("informaticsSkill");
	// ScriptLogger logger = (ScriptLogger)inReq.getPageValue("log");
	// informaticsManager.processAll(logger);
	// }
}
