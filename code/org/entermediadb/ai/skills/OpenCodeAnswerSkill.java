package org.entermediadb.ai.skills;

import java.util.Map;

import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.agentjobs.AgentJobOrchestrator;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.mcp.client.OpenCodeClient;
import org.entermediadb.mcp.client.SessionStatus;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.MultiValued;
import org.openedit.OpenEditException;
import org.openedit.util.JSONParser;

/**
 * OpenCodeAnswerSkill - Answers an opencode form for an agentjobstep.
 * Context: "agentjobstepid" (required), plus one "{formid}_{fieldkey}" value per form field as posted
 * by agent_job_showjobplan.html (multiselect options post "{formid}_{fieldkey}__{optionvalue}" = "true").
 */
public class OpenCodeAnswerSkill extends BaseSkill
{
	@Override
	public void process(AgentContext inContext)
	{
		String stepid = (String) inContext.getContextValue("agentjobstepid");
		MultiValued step = (MultiValued) getMediaArchive().getData("agentjobstep", stepid);
		if (step == null || !"question".equals(step.get("status")))
		{
			throw new OpenEditException("OpenCodeAnswerSkill: step " + stepid + " is not waiting on a question");
		}
		String formjson = step.get("pendingform");
		if (formjson == null || formjson.isEmpty())
		{
			throw new OpenEditException("OpenCodeAnswerSkill: step " + stepid + " has no pending form");
		}
		JSONObject form = new JSONParser().parse(formjson);
		String formid = (String) form.get("id");
		JSONObject answer = collectAnswer(inContext, formid, (JSONArray) form.get("fields"));

		AgentJobOrchestrator orchestrator = (AgentJobOrchestrator) getMediaArchive().getBean("agentJobOrchestrator");
		OpenCodeClient client = orchestrator.getOpenCodeClient();
		SessionStatus status = client.loadStatus(stepid);
		if (status == null)
		{
			throw new OpenEditException("OpenCodeAnswerSkill: no opencode session waiting for step " + stepid);
		}
		if (!client.replyToForm(status.getSessionId(), formid, answer))
		{
			throw new OpenEditException("OpenCodeAnswerSkill: opencode rejected the reply for step " + stepid);
		}

		step.setValue("status", "running");
		step.setValue("pendingquestion", null);
		step.setValue("pendingpermissionid", null);
		step.setValue("pendingform", null);
		getMediaArchive().saveData("agentjobstep", step);

		LlmResponse response = new BasicLlmResponse();
		response.setMessage("Question answered: " + answer.toJSONString());
		inContext.setLastResponse(response);
		super.process(inContext);
	}

	/**
	 * Converts the posted string values to the types opencode's Form.Answer expects. Blank fields are
	 * left out so opencode applies the field's default.
	 */
	protected JSONObject collectAnswer(AgentContext inContext, String inFormId, JSONArray inFields)
	{
		JSONObject answer = new JSONObject();
		if (inFields == null)
		{
			return answer;
		}
		for (Object item : inFields)
		{
			Map field = (Map) item;
			String key = (String) field.get("key");
			String type = (String) field.get("type");
			String prefix = inFormId + "_" + key;
			if ("multiselect".equals(type))
			{
				JSONArray values = new JSONArray();
				JSONArray options = (JSONArray) field.get("options");
				if (options != null)
				{
					for (Object option : options)
					{
						String value = (String) ((Map) option).get("value");
						if ("true".equals(inContext.getContextValue(prefix + "__" + value)))
						{
							values.add(value);
						}
					}
				}
				answer.put(key, values);
				continue;
			}
			if ("external".equals(type))
			{
				continue;
			}
			String value = (String) inContext.getContextValue(prefix);
			if (value == null || value.trim().isEmpty())
			{
				continue;
			}
			value = value.trim();
			try
			{
				if ("boolean".equals(type))
				{
					answer.put(key, Boolean.valueOf(value));
				}
				else if ("integer".equals(type))
				{
					answer.put(key, Long.valueOf(value));
				}
				else if ("number".equals(type))
				{
					answer.put(key, Double.valueOf(value));
				}
				else
				{
					answer.put(key, value);
				}
			}
			catch (NumberFormatException e)
			{
				throw new OpenEditException("OpenCodeAnswerSkill: " + key + " must be a number, got " + value);
			}
		}
		return answer;
	}
}
