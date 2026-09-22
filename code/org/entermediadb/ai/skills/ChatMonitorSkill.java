package org.entermediadb.ai.skills;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.json.simple.JSONObject;
import org.openedit.MultiValued;

public class ChatMonitorSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(ChatMonitorSkill.class);

	@Override
	public void process(AgentContext inAgentContext)
	{
		ChatMessageContext messageContext = (ChatMessageContext) inAgentContext;
		MultiValued agentmessage = messageContext.getAgentMessage();
		// MultiValued currentfunction = messageContext.getCurrentFunction();

		MultiValued usermessage = (MultiValued) getMediaArchive().getCachedData("chatterbox", agentmessage.get("replytoid"));
		String query = usermessage.get("message");

		String functionpath = inAgentContext.getCurrentScenario().getId();
		String function = inAgentContext.getCurrentAutomationStep().getAutomationStepData().getId();

		functionpath = functionpath + "." + function;

		inAgentContext.put("startup_scenario", functionpath);

		// Move to its own skill, next step is parse text
		inAgentContext.put("userquery", query);

		// Collection<Data> toplevelfunctions = getMediaArchive().query("aifunction").exact("toplevel",
		// true).search();
		// inAgentContext.put("toplevelfunctions", toplevelfunctions);

		LlmConnection llmconnection = getMediaArchive().getLlmConnection("thinking");

		LlmResponse response = llmconnection.callToolsFunction(inAgentContext, "chat_monitor");

		log.info(response.getRawResponse());
		// log.info(JsonOutput.prettyPrint(payload.toJSONString()));

		JSONObject structuredResponse = response.getToolsResponse();

		String selected_tool = (String) structuredResponse.get("name");

		inAgentContext.addContext("selectedscenario", selected_tool);

		inAgentContext.addContext("arguments", structuredResponse.get("arguments"));

		if (selected_tool.equals("showfriendlyresponse"))
		{

			llmconnection = getMediaArchive().getLlmConnection("localrender");
			response = llmconnection.renderLocalAction(inAgentContext, "chat_detect_showresponse");
			response.setNextAutomationStep("chatMonitor"); // Stay in this skill?
			inAgentContext.setLastResponse(response);

			AutomationStep skillEnabled = messageContext.getCurrentAutomationStep();

			messageContext.fireStatusComplete(skillEnabled);

			return;
		}

		super.process(messageContext);

	}

}
