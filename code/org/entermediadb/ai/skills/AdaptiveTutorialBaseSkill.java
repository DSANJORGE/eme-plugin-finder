package org.entermediadb.ai.skills;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.TutorMessageContext;
import org.entermediadb.ai.automation.RunningScenario;
import org.entermediadb.ai.llm.AutomationStep;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.util.JSONParser;

public class AdaptiveTutorialBaseSkill extends BaseSkill
{
	public void endTutorial(TutorMessageContext tutorMessageContext)
	{
		RunningScenario scenario = tutorMessageContext.getCurrentScenario();

		AutomationStep nextAutomationStep = scenario.findEnabled("chat_tutor_end");

		TutorMessageContext nextContext = (TutorMessageContext) scenario.createAgentContext(tutorMessageContext, nextAutomationStep);
		scenario.runProcess(nextAutomationStep, nextContext, true);
	}

	public Map<String, Double> getCognitiveLevelPoints()
	{
		Collection<MultiValued> mcqcognitivelevels = getMediaArchive().query("mcqcognitivelevel").all().search();
		Map<String, Double> cognitivelevelpoints = new HashMap<>();
		for (MultiValued level : mcqcognitivelevels)
		{
			cognitivelevelpoints.put(level.getId(), level.getDouble("points"));
		}
		return cognitivelevelpoints;
	}

	public Map<String, Double> getAnswerConfidenceBonus()
	{
		Collection<MultiValued> answerconfidences = getMediaArchive().query("answerconfidence").all().search();
		Map<String, Double> answerconfidencebonus = new HashMap<>();
		for (MultiValued confidence : answerconfidences)
		{
			answerconfidencebonus.put(confidence.getId(), confidence.getDouble("bonuspercentage"));
		}
		return answerconfidencebonus;
	}

	protected JSONArray getReleaventChatHistory(String sectionId, String channelId, String userId)
	{
		Collection<MultiValued> messages = getMediaArchive().query("chatterbox").exact("channel", channelId).orgroup("user", userId + ",agent").not("messagetype", "system").sort("dateDown").search();

		List<MultiValued> releaventMessages = new ArrayList<>();

		for (MultiValued message : messages)
		{
			Map<String, Object> agentContext = getAgentContext(message);	

			if (agentContext == null || !sectionId.equals(agentContext.get("sectionid")))
			{
				continue;
			}
			if ("usercomment".equals(agentContext.get("messagerendertype")) || "answereval".equals(agentContext.get("messagerendertype")))
			{
				releaventMessages.add(message);
			}
			else if ("question".equals(agentContext.get("messagerendertype")))
			{
				releaventMessages.add(message);
				break;
			}
		}
		Collections.reverse(releaventMessages);

		JSONArray chatHistory = new JSONArray();

		Collection<MultiValued> components = getMediaArchive().query("componentcontent").exact("componentsectionid", sectionId).sort("ordering").search();
		for (MultiValued component : components)
		{
			String content = component.get("content");
			if (content == null || content.length() == 0)
			{
				continue;
			}
			JSONObject historyItem = new JSONObject();
			historyItem.put("role", "assistant");
			historyItem.put("content", content);
			chatHistory.add(historyItem);
		}

		for (MultiValued msg : releaventMessages)
		{
			Map<String, Object> agentContext = getAgentContext(msg);
			buildHistoryFromAgentContext(agentContext, chatHistory);
		}

		return chatHistory;
	}

	private Map<String, Object> getAgentContext(MultiValued message)
	{
		Object agentContextObj = message.getValue("agentcontextvalues");
		Map<String, Object> agentContext = null;
		if( agentContextObj instanceof String)
		{
			String agentContextStr = (String) agentContextObj;
			agentContext = new JSONParser().parseMap(agentContextStr);
			message.setValue("agentcontextvalues",agentContext); //For next time
		}	
		else
		{
			agentContext = (Map<String, Object>) agentContextObj;
		}
		return agentContext;
	}

	protected void buildHistoryFromAgentContext(Map<String, Object> agentContext, JSONArray chatHistory)
	{
		if ("question".equals(agentContext.get("messagerendertype")))
		{
			StringBuilder ctx = new StringBuilder();

			Map<String, Object> question = (Map<String, Object>) agentContext.get("question");
			if (question != null)
			{
				ctx.append(question.get("question")).append(" \n");
				Map<String, Object> options = (Map<String, Object>) question.get("options");
				// sort options
				List<String> optionKeys = new ArrayList<>(options.keySet());
				Collections.sort(optionKeys);

				for (String option : optionKeys)
				{
					if (options.get(option) != null)
					{
						ctx.append(option).append(": " + options.get(option)).append(" \n");
					}
				}

				if (question.get("correctoption") != null)
				{
					ctx.append("\n ").append("Correct Option: " + question.get("correctoption"));
				}
				if (question.get("rationale") != null)
				{
					ctx.append("\n ").append("Rationale: " + question.get("rationale"));
				}
			}
			String content = ctx.toString();
			if (content.length() == 0)
			{
				return;
			}
			JSONObject historyItem = new JSONObject();

			historyItem.put("role", "assistant");
			historyItem.put("content", content);

			chatHistory.add(historyItem);
		}
		else if ("answereval".equals(agentContext.get("messagerendertype")))
		{
			StringBuilder ctx = new StringBuilder();

			ctx.append("Answer: " + agentContext.get("selectedoption"));
			ctx.append(" \n ").append("Confidence: " + agentContext.get("confidence"));

			String content = ctx.toString();
			if (content.length() == 0)
			{
				return;
			}
			JSONObject historyItem = new JSONObject();

			historyItem.put("role", "user");
			historyItem.put("content", content);

			chatHistory.add(historyItem);

			Boolean iscorrect = (Boolean) agentContext.get("iscorrect");
			if (iscorrect == null)
			{
				return;
			}

			historyItem = new JSONObject();
			historyItem.put("role", "assistant");
			historyItem.put("content", iscorrect ? "Correct!" : "Incorrect!");

			chatHistory.add(historyItem);
		}
		else if ("usercomment".equals(agentContext.get("messagerendertype")))
		{
			String content = (String) agentContext.get("componentcontent");

			if (content == null || content.length() == 0)
			{
				return;
			}
			JSONObject historyItem = new JSONObject();
			historyItem.put("role", "user");
			historyItem.put("content", content);

			chatHistory.add(historyItem);
		}
	}

}
