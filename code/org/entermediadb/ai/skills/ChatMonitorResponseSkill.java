package org.entermediadb.ai.skills;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.automation.RunningScenario;
import org.entermediadb.ai.llm.AutomationStep;
import org.openedit.MultiValued;

public class ChatMonitorResponseSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(ChatMonitorResponseSkill.class);

	@Override
	public void startupScenario(AgentContext inContext)
	{
		// super.startupScenario(inContext);
		// dont send hi
	}

	@Override
	public void process(AgentContext inAgentContext)
	{
		ChatMessageContext messageContext = (ChatMessageContext) inAgentContext;
		MultiValued agentmessage = messageContext.getAgentMessage();

		MultiValued usermessage = (MultiValued) getMediaArchive().getCachedData("chatterbox", agentmessage.get("replytoid"));

		String query = usermessage.get("message");

		// reset messagereload
		inAgentContext.putContextValue("messagereload", false);

		// TODO Fix entityid for collections?
		String entityid = inAgentContext.get("entityid");
		String selectedscenario = (String) inAgentContext.getContextValue("selectedscenario");
		if (selectedscenario == null)
		{
			log.error("No scenario selected for query: " + query);
			return;
		}
		if (!selectedscenario.contains("."))
		{
			log.error("Selected scenario needs the format: scenario.skillenabled. Selected scenario:" + selectedscenario);
			return;
		}
		inAgentContext.getCurrentScenario().runProcess(selectedscenario, inAgentContext);

	}

}
