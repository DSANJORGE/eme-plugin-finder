package org.entermediadb.ai.skills;

import java.util.Collection;
import java.util.Iterator;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.Schema;
import org.entermediadb.ai.assistant.AssistantManager;
import org.entermediadb.ai.assistant.GuideStatus;
import org.entermediadb.ai.classify.EmbeddingManager;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.asset.Asset;
import org.json.simple.JSONArray;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.OpenEditException;
import org.openedit.hittracker.HitTracker;

public class QuestionsSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(QuestionsSkill.class);

	@Override
	public void process(AgentContext inAgentContext)
	{
		ChatMessageContext messageContext = (ChatMessageContext) inAgentContext;
		MultiValued inAgentMessage = messageContext.getAgentMessage();
		String agentFn = messageContext.getCurrentAutomationStep().getEnabledId();

		inAgentMessage.setValue("chatmessagestatus", "completed");

		String entityid = messageContext.get("entityid");
		String entitymoduleid = messageContext.get("entitymoduleid");

		Data entity = getMediaArchive().getCachedData(entitymoduleid, entityid);
		messageContext.addContext("entity", entity);

		Data entitymodule = getMediaArchive().getCachedData("module", entitymoduleid);
		messageContext.addContext("entitymodule", entitymodule);

		Collection<GuideStatus> statuses = getAssistantManager().getGuideStatus(entitymodule, entity);
		messageContext.addContext("statuses", statuses);

		/*
		 * for(GuideStatus stat : statuses) { if(!stat.isReady()) { messageContext.setValue("wait", 1000L);
		 * messageContext.setRunFunctionName(messageContext.getFunctionName());
		 * 
		 * return null; } }
		 */

		Collection aisuggestions = getMediaArchive().query("aisuggestion").exact("entityid", entity).search();
		messageContext.addContext("suggestions", aisuggestions);

		LlmConnection llmconnection = getMediaArchive().getLlmConnection("localrender"); // Should stay
		LlmResponse response = llmconnection.renderLocalAction(messageContext, agentFn);
		if (aisuggestions.isEmpty())
		{
			response.setExecAutomationSkill("question_create_suggestions");
			messageContext.setLastResponse(response);
			super.process(inAgentContext);
		}
		else
		{
			response.setNextAutomationStep("question_ask");
			messageContext.setLastResponse(response);
			messageContext.setWaitTime(null);
			AutomationStep skillEnabled = messageContext.getCurrentAutomationStep();
			messageContext.fireStatusComplete(skillEnabled);

		}

		return;

	}

}
