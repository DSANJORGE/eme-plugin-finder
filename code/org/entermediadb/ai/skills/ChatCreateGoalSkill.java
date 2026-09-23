package org.entermediadb.ai.skills;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.tasks.GoalManager;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.MultiValued;

public class ChatCreateGoalSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(ChatCreateGoalSkill.class);

	@Override
	public void process(AgentContext inAgentContext)
	{
		ChatMessageContext messageContext = (ChatMessageContext) inAgentContext;
		MultiValued agentmessage = messageContext.getAgentMessage();
		if (agentmessage == null)
		{
			return;
		}

		messageContext.fireStatusStarting(messageContext.getCurrentAutomationStep());

		MultiValued usermessage = (MultiValued) getMediaArchive().getCachedData("chatterbox", agentmessage.get("replytoid"));

		String userid = usermessage.get("user");
		String collectionid = inAgentContext.get("entityid"); // EmeChatDetectSkill sets the right collectionid in entityid
		JSONObject arguments = (JSONObject) inAgentContext.getContextValue("arguments");
		String taskdescription = (String) arguments.get("task");
		agentmessage.setValue("message", taskdescription);
		agentmessage.setValue("useralias", userid);
		getMediaArchive().saveData("chatterbox", agentmessage);

		Data goal = getGoalManager().createGoal(userid, agentmessage, collectionid);
		messageContext.putContextValue("goal", goal);
		super.process(messageContext);

		// AutomationStep skillEnabled = messageContext.getCurrentAgentEnable();
		// fireStatusComplete(skillEnabled);

		// getMediaArchive().fireSharedMediaEvent("goaltask/goalcreated");

		return;
	}

	private GoalManager getGoalManager()
	{
		GoalManager goalm = (GoalManager) getMediaArchive().getBean("goalManager");
		return goalm;
	}

}
