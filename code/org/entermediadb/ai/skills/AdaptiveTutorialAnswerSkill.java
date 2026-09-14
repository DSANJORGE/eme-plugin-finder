package org.entermediadb.ai.skills;

import java.util.Date;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.TutorMessageContext;
import org.entermediadb.ai.automation.RunningScenario;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.data.Searcher;

public class AdaptiveTutorialAnswerSkill extends AdaptiveTutorialBaseSkill
{
	private static final Log log = LogFactory.getLog(AdaptiveTutorialAnswerSkill.class);

	@Override
	public void process(AgentContext inAgentContext)
	{
		TutorMessageContext tutorMessageContext = (TutorMessageContext) inAgentContext;

		String channelid = tutorMessageContext.getChannel().getId();
		String questionid = (String) tutorMessageContext.getContextValue("questionid");
		String confidence = (String) tutorMessageContext.getContextValue("confidence");
		String selectedoption = (String) tutorMessageContext.getContextValue("selectedoption");
		// TestU local patch: topic (tutorial) and subtopic (section) of the
		// attempt, so the tutor can reason about progress per section. The
		// tutor channel is bound to the tutorial when the app sends none.
		// The request's context_sectionid (channel context) must win over the
		// tutorial cursor the continue skill stored on the agent message: the
		// app answers questions from any section without moving that cursor.
		String sectionid = (String) tutorMessageContext.getContextValue("sectionid");
		log.info("TestU answer skill: request sectionid=" + sectionid + " message sectionid=" + tutorMessageContext.getContextValue("sectionid") + " local="
			+ tutorMessageContext.getContext().get("sectionid") + " root=" + tutorMessageContext.getRootContext().getContext().get("sectionid"));
		if (sectionid == null)
		{
			sectionid = (String) tutorMessageContext.getContextValue("sectionid");
		}
		String tutorialid = (String) tutorMessageContext.getContextValue("tutorialid");
		if (tutorialid == null)
		{
			tutorialid = tutorMessageContext.getChannel().get("dataid");
		}

		if (channelid == null || questionid == null || selectedoption == null)
		{
			return;
		}

		Data question = getMediaArchive().getData("entityquestion", questionid);
		if (question == null)
		{
			return;
		}

		Searcher searcher = getMediaArchive().getSearcher("tutoranswer");
		String userid = tutorMessageContext.getUserProfile().getUser().getId();
		boolean iscorrect;
		// TestU learning engine v1: the app stores the answer first through services/testu/learn/answer.json, which
		// verifies mode, scope, hierarchy and hint level, then sends only its id here for the tutor's feedback.
		Object answerid = tutorMessageContext.getContextValue("answerid");
		if (answerid != null && !answerid.toString().trim().isEmpty())
		{
			Data stored = (Data) searcher.searchById(answerid.toString().trim());
			if (stored == null || !userid.equals(stored.get("user")) || !questionid.equals(stored.get("entityquestion")))
			{
				log.info("TestU answer skill: answerid " + answerid + " is not this user's answer to " + questionid);
				return;
			}
			// Feedback follows the stored, verified answer, not the message's own values.
			selectedoption = stored.get("selectedoption");
			confidence = stored.get("answerconfidence");
			iscorrect = "true".equals(String.valueOf(stored.getValue("iscorrect")));
			if (stored.get("channel") == null)
			{
				stored.setValue("channel", channelid);
				searcher.saveData(stored);
			}
		}
		else
		{
			iscorrect = selectedoption.equals(question.get("correctoption"));

			Map<String, Double> cognitivelevelpoints = getCognitiveLevelPoints();
			Map<String, Double> answerconfidencebonus = getAnswerConfidenceBonus();

			double allottedpoints = cognitivelevelpoints.getOrDefault(question.get("mcqcognitivelevel"), 0.0);

			double points = 0.0;
			if (iscorrect)
			{
				points = allottedpoints;
			}

			double bonus = allottedpoints * (answerconfidencebonus.getOrDefault(confidence, 0.0) / 100.0);

			Data answer = searcher.createNewData();
			answer.setValue("channel", channelid);
			answer.setValue("entityquestion", questionid);
			answer.setValue("answerconfidence", confidence);
			answer.setValue("selectedoption", selectedoption);
			answer.setValue("iscorrect", iscorrect);
			answer.setValue("pointsearned", points);
			answer.setValue("bonusearned", bonus);
			answer.setValue("entitytutorial", tutorialid);
			answer.setValue("componentsection", sectionid);
			answer.setValue("user", userid);
			answer.setValue("datecreated", new Date());
			answer.setValue("lastpenalty", new Date());
			// Not verified by answer.json (older app builds): stored as legacy, which never advances the learning sequence.
			// Client-sent mode, scope and hint level are ignored.
			answer.setValue("mode", "legacy");

			searcher.saveData(answer);
		}

		tutorMessageContext.putContextValue("iscorrect", iscorrect);
		tutorMessageContext.putContextValue("question", question);
		tutorMessageContext.putContextValue("confidence", confidence);
		tutorMessageContext.putContextValue("selectedoption", selectedoption);

		LlmConnection llmconnection = getMediaArchive().getLlmConnection("thinking");
		LlmResponse response = llmconnection.callStructure(tutorMessageContext, "chat_tutor_feedback");

		JSONObject feedback = response.getResponsePayload();
		String feedbackText = (String) feedback.get("message");
		if (feedbackText == null)
		{
			tutorMessageContext.error("No feedback " + feedbackText);
			return;
		}

		LlmResponse llmResponse = new BasicLlmResponse();
		llmResponse.setMessage(feedbackText);

		tutorMessageContext.setLastResponse(llmResponse);

		tutorMessageContext.putContextValue("messagerendertype", "answereval");

		AutomationStep skillEnabled = tutorMessageContext.getCurrentAutomationStep();
		tutorMessageContext.fireStatusComplete(skillEnabled);

		Data agentmessage = tutorMessageContext.getAgentMessage();

		agentmessage.setValue("id", tutorMessageContext.getContextValue("tutorialid") + "_progressupdate");
		agentmessage.setValue("messagetype", "system");

		RunningScenario scenario = tutorMessageContext.getCurrentScenario();

		AutomationStep nextAutomationStep = scenario.findEnabled("chat_tutor_progress");
		TutorMessageContext nextContext = (TutorMessageContext) scenario.createAgentContext(tutorMessageContext, nextAutomationStep);

		scenario.runProcess(nextAutomationStep, nextContext, true);
	}
}
