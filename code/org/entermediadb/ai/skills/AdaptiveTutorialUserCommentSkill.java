package org.entermediadb.ai.skills;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.TutorMessageContext;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class AdaptiveTutorialUserCommentSkill extends AdaptiveTutorialBaseSkill
{
	private static final Log log = LogFactory.getLog(AdaptiveTutorialUserCommentSkill.class);

	@Override
	public void process(AgentContext inAgentContext)
	{
		TutorMessageContext tutorMessageContext = (TutorMessageContext) inAgentContext;

		String channelid = tutorMessageContext.getChannel().getId();
		String tutorialid = (String) tutorMessageContext.getContextValue("tutorialid");
		String sectionid = (String) tutorMessageContext.getContextValue("sectionid");
		String usermessage = (String) tutorMessageContext.getUserMessage().get("message");
		String userId = tutorMessageContext.getUserProfile().getUser().getId();

		LlmConnection llmconnection = getMediaArchive().getLlmConnection("embedding");

		JSONArray chatHistory = getReleaventChatHistory(sectionid, channelid, userId);

		Collection<String> parentIds = getAssistantManager().findDocIdsForEntity("entitytutorial", tutorialid);

		if (parentIds.isEmpty())
		{
			log.info("No parent ids found!");
			return;
		}

		Map payload = new HashMap();
		payload.put("query", usermessage);
		payload.put("parent_ids", parentIds);
		payload.put("chat_history", chatHistory);

		log.info("Sending /chat to embedding server with query: " + usermessage + ", parent_ids: " + parentIds.size() + ", history size: " + chatHistory.size());
		log.info("Chat payload: " + payload);

		LlmResponse res = llmconnection.callJson("/chat", payload);

		JSONObject contentsJson = res.getRawResponse();

		String answer = (String) contentsJson.get("answer");
		if (answer == null)
		{
			tutorMessageContext.error("No answer found " + answer);
			return;
		}
		LlmResponse llmResponse = new BasicLlmResponse();
		llmResponse.setMessage(answer);

		tutorMessageContext.setLastResponse(llmResponse);

		tutorMessageContext.putContextValue("messagerendertype", "agentcomment");

		AutomationStep skillEnabled = tutorMessageContext.getCurrentAutomationStep();
		tutorMessageContext.fireStatusComplete(skillEnabled);
	}
}
