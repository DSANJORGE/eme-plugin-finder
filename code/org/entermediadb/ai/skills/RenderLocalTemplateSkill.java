package org.entermediadb.ai.skills;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;

public class RenderLocalTemplateSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(RenderLocalTemplateSkill.class);

	@Override
	public void process(AgentContext inAgentContext)
	{
		ChatMessageContext messageContext = (ChatMessageContext) inAgentContext;
		// MultiValued agentmessage = messageContext.getAgentMessage();
		// agentmessage.setValue("chatmessagestatus", "completed");

		LlmConnection llmconnection = getMediaArchive().getLlmConnection("localrender"); // Should stay
		// search_start
		String template = inAgentContext.getCurrentAutomationStep().getEnabledId();
		LlmResponse response = llmconnection.renderLocalAction(inAgentContext, template);

		// String nextskill = (String) inAgentContext.getContextValue("nextskillenabled");
		// if (nextskill != null)
		// {
		// response.setNextSkillEnabled(nextskill);
		// }
		// log.info(response.getMessage());
		messageContext.setLastResponse(response);
		// messageContext.log("sent" + response.getMessagePlain());

		AutomationStep skillEnabled = messageContext.getCurrentAutomationStep();
		messageContext.fireStatusComplete(skillEnabled);
	}

}
