package org.entermediadb.ai.skills;

import java.io.File;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.agentjobs.AgentJobOrchestrator;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.mcp.client.OpenCodeClient;
import org.entermediadb.mcp.client.SessionStatus;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.OpenEditException;
import org.openedit.repository.ContentItem;

/**
 * OpenCodeRunnerSkill - Starts (or resumes) an opencode server session for the current
 * agentjobstep and blocks until the session goes idle, hits an error, or opencode asks a
 * permission question that needs a human answer.
 *
 * Usage in AgentContext: - "userrequest" (required, set by OpenCodeUserRequestSkill in chat) - The prompt sent to opencode - "workingpath"
 * (optional) - Directory the opencode session runs in; defaults to one level above
 * getMediaArchive().getRootDirectory() - "agentjobstep" - The MultiValued step record; its id keys
 * the OpenCodeClient session map so re-running this skill resumes the same opencode session
 * instead of starting a new one.
 *
 * Results: - On completion, "commandoutput" holds the assistant's final reply text and it's also
 * set as inContext.getLastResponse(). - If opencode raises a permission request (a tool call
 * awaiting approval) that isn't resolved within the wait budget, the agentjobstep is left with
 * status "question" or "securityprompt" plus "pendingquestion"/"pendingpermissionid", and this method returns
 * without marking the step complete. Answering that question (via
 * OpenCodeClient#replyToPermission) and re-running this step resumes the same session.
 */
public class OpenCodeRunnerSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(OpenCodeRunnerSkill.class);

	// How long a single poll blocks waiting on new /event activity before we check the overall budget.
	protected static final long POLL_TIMEOUT_MS = 30_000L;

	// Overall wall-clock budget for one invocation before giving up as timed out.
	protected static final long MAX_WAIT_MS = 20 * 60 * 1000L;

	@Override
	public void process(AgentContext inContext)
	{
		AgentJobOrchestrator orchestrator = (AgentJobOrchestrator) getMediaArchive().getBean("agentJobOrchestrator");
		String query = (String) inContext.getContextValue("userrequest");
		if (query == null || query.trim().isEmpty())
		{
			// In chat, OpenCodeUserRequestSkill runs first and sets userrequest and agentjobstep
			log.info("OpenCodeRunnerSkill: no userrequest, waiting for input");
			return;
		}

		String workingpath = (String) inContext.getContextValue("workingpath");
		if (workingpath == null || workingpath.trim().isEmpty())
		{
			ContentItem root = getMediaArchive().getPageManager().getRepository().get("/");

			workingpath = new File(root.getAbsolutePath()).getParentFile().getAbsolutePath();

			log.info("OpenCodeRunnerSkill: No workingpath provided, defaulting to " + workingpath);
		}

		MultiValued agentjobstep = (MultiValued) inContext.getContextValue("agentjobstep");

		SessionStatus status;
		OpenCodeClient client;
		try
		{
			client = orchestrator.getOpenCodeClient();
			client.connectToServer(); //start listening
			status = client.loadStatus(agentjobstep.getId());
			if (status == null)
			{
				status = client.startSessionId(agentjobstep, workingpath, query);
			}

			long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
			do
			{
				status = client.advanceSession(inContext.getScriptLogger(),agentjobstep.getId(), POLL_TIMEOUT_MS);
				// Save progress from time to time so the step shows what opencode has said so far.
				saveMarkdown(client, status);
			}
			while (!status.isCompleted() && status.getPendingPermissionId() == null
					&& System.currentTimeMillis() < deadline);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new OpenEditException("OpenCodeRunnerSkill interrupted waiting on opencode session in " + workingpath, e);
		}
		catch (Exception e)
		{
			log.error("OpenCodeRunnerSkill error running opencode in " + workingpath, e);
			throw new OpenEditException("OpenCodeRunnerSkill error running opencode in " + workingpath, e);
		}

		if (status.getPendingPermissionId() != null)
		{
			// opencode is blocked on a tool-call approval. Leave the step for a human to answer
			// instead of marking it complete; AgentJobOrchestrator.runSkill only stamps "complete"
			// when the step's status is still "running".
			// "question" or "securityprompt" (see jobstatus.xml); the prompt text replaces the markdown.
			String pendingStatus = status.getPendingStatus() != null ? status.getPendingStatus() : "securityprompt";
			agentjobstep.setValue("status", pendingStatus);
			agentjobstep.setValue("markdowncontent", status.getCurrentQuestion());
			agentjobstep.setValue("pendingquestion", status.getCurrentQuestion());
			agentjobstep.setValue("pendingpermissionid", status.getPendingPermissionId());
			// The form's fields are rendered by agent_job_showjobplan.html via AgentJob.getPendingForm
			JSONObject form = status.getPendingForm();
			agentjobstep.setValue("pendingform", form == null ? null : form.toJSONString());
			getMediaArchive().saveData("agentjobstep", agentjobstep);

			LlmResponse response = new BasicLlmResponse();
			response.setMessage(status.getCurrentQuestion());
			inContext.setLastResponse(response);
			return;
		}

		if (!status.isCompleted())
		{
			throw new OpenEditException("OpenCodeRunnerSkill: opencode session " + status.getSessionId()
					+ " did not finish within " + MAX_WAIT_MS + "ms");
		}

		if (status.getError() != null)
		{
			throw new OpenEditException("OpenCodeRunnerSkill: opencode session " + status.getSessionId()
					+ " failed: " + status.getError());
		}

		List<JSONObject> messages = client.getMessages(status.getSessionId());
		String output = extractLastAssistantText(messages);
		saveMarkdown(status, output);

		inContext.put("commandoutput", output);
		inContext.put("markdowncontent", output);
		LlmResponse response = new BasicLlmResponse();
		response.setMessage(output);
		inContext.setLastResponse(response);

		super.process(inContext);
	}

	/**
	 * Fetches the session's latest assistant text and saves it as the step's markdowncontent.
	 * Best effort: a failure here must not break the running session.
	 */
	protected void saveMarkdown(OpenCodeClient inClient, SessionStatus inStatus)
	{
		try
		{
			saveMarkdown(inStatus, extractLastAssistantText(inClient.getMessages(inStatus.getSessionId())));
		}
		catch (Exception e)
		{
			log.warn("OpenCodeRunnerSkill could not save progress for session " + inStatus.getSessionId(), e);
		}
	}

	protected void saveMarkdown(SessionStatus inStatus, String inMarkdown)
	{
		if (inMarkdown == null || inMarkdown.isEmpty() || inStatus.getAgentJobStep() == null)
		{
			return;
		}
		Data step = inStatus.getAgentJobStep();
		if (inMarkdown.equals(step.get("markdowncontent")))
		{
			return;
		}
		step.setValue("markdowncontent", inMarkdown);
		getMediaArchive().saveData("agentjobstep", step);
	}

	/**
	 * Finds the most recent assistant message (per the opencode SDK's {info: {role}, parts:
	 * [{type, text}]} message shape) and concatenates its text parts.
	 */
	protected String extractLastAssistantText(List<JSONObject> inMessages)
	{
		if (inMessages == null)
		{
			return null;
		}
		for (int i = inMessages.size() - 1; i >= 0; i--)
		{
			JSONObject message = inMessages.get(i);
			Object infoObj = message.get("info");
			if (!(infoObj instanceof JSONObject) || !"assistant".equals(((JSONObject) infoObj).get("role")))
			{
				continue;
			}
			Object partsObj = message.get("parts");
			if (!(partsObj instanceof JSONArray))
			{
				continue;
			}
			StringBuilder text = new StringBuilder();
			for (Object partObj : (JSONArray) partsObj)
			{
				if (partObj instanceof JSONObject && "text".equals(((JSONObject) partObj).get("type")))
				{
					Object partText = ((JSONObject) partObj).get("text");
					if (partText != null)
					{
						text.append(partText);
					}
				}
			}
			return text.toString();
		}
		return null;
	}

}
