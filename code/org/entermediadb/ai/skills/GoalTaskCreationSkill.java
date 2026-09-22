package org.entermediadb.ai.skills;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.json.simple.JSONObject;
import org.openedit.Data;

public class GoalTaskCreationSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(GoalTaskCreationSkill.class);

	@Override
	public void process(AgentContext inContext)
	{

		Data goal = (Data) inContext.getContextValue("goal");
		if (goal != null)
		{
			createOutline(inContext, goal);
			getMediaArchive().saveData("projectgoal", goal);

			BasicLlmResponse response = new BasicLlmResponse();
			response.setNextAutomationStep("emeteamchat_responder_respond");

			inContext.putContextValue("messagereload", true);
			inContext.setLastResponse(response);
			super.process(inContext);

			return;
		}

		Collection<Data> goals = getMediaArchive().query("projectgoal").exact("projectstatus", "open").exact("taggedbyllm", "false").search();
		if (goals == null || goals.isEmpty())
		{
			return;
		}
		List<Data> tosave = new ArrayList<>();
		for (Data agoal : goals)
		{
			createOutline(inContext, agoal);
			tosave.add(agoal);

		}
		getMediaArchive().saveData("projectgoal", tosave);

		log.info("Completed processing goals and creating tasks");
		super.process(inContext);

		// ToDo: Create a new skill to assign tasks to Agents based in their roles

	}

	private void createOutline(AgentContext inContext, Data goal)
	{
		String goalid = goal.getId();
		log.info("Processing Goal: " + goalid);

		inContext.put("goalid", goalid);
		inContext.put("goal", goal);

		// Get the docids for the collection and set it in the context
		String collectionid = goal.get("collectionid");
		String ownerid = goal.get("owner");
		Collection<String> docids = loadOwnerKnowlege(collectionid, ownerid);
		inContext.putContextValue("docids", docids);

		String goaldescription = goal.get("name");

		// Get a list of tasks (embeding call, question suggest like) that is appropied for the goal
		LlmConnection llmconnection = getMediaArchive().getLlmConnection("embedding");
		JSONObject payload = new JSONObject();

		// You are a project manager expert. Given a context and a user query, generate a list of relevant
		// task list.

		String prompt = "Create a maximum of 3 detailed tasks items for a goal of " + goaldescription;

		payload.put("query", prompt);
		payload.put("parent_ids", docids);
		// log.info("Sending: " + payload);
		LlmResponse response = llmconnection.callJson("/create_outline", payload);

		JSONObject outlineJson = response.getRawResponse();
		Collection<String> outline = (Collection<String>) outlineJson.get("outline");

		createTasksForGoal(inContext, goal, outline);

		goal.setValue("taggedbyllm", "true");
	}

	public void createTasksForGoal(AgentContext inContext, Data goal, Collection<String> inOutline)
	{
		List<Data> tosave = new ArrayList<>();
		for (String step : inOutline)
		{
			Data task = getMediaArchive().getSearcher("goaltask").createNewData();
			task.setValue("comment", step);
			task.setValue("projectgoal", goal.getId());
			task.setValue("taskstatus", "0");
			task.setValue("creationdate", new Date());
			task.setValue("completedby", goal.get("owner"));

			tosave.add(task);
		}

		getMediaArchive().saveData("goaltask", tosave);

	}
}
