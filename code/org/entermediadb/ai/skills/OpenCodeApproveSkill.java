package org.entermediadb.ai.skills;

import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.agentjobs.AgentJobOrchestrator;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.mcp.client.OpenCodeClient;
import org.entermediadb.mcp.client.SessionStatus;
import org.openedit.MultiValued;
import org.openedit.OpenEditException;

/**
 * OpenCodeApproveSkill - Answers an opencode security (permission) prompt for an agentjobstep.
 * Context: "agentjobstepid" (required), "decision" = once | always | reject (default once).
 */
public class OpenCodeApproveSkill extends BaseSkill
{
	@Override
	public void process(AgentContext inContext)
	{
		String stepid = (String) inContext.getContextValue("agentjobstepid");
		MultiValued step = (MultiValued) getMediaArchive().getData("agentjobstep", stepid);
		if (step == null || !"securityprompt".equals(step.get("status")))
		{
			throw new OpenEditException("OpenCodeApproveSkill: step " + stepid + " is not waiting on a security prompt");
		}
		String decision = (String) inContext.getContextValue("decision");
		if (!"always".equals(decision) && !"reject".equals(decision))
		{
			decision = "once";
		}

		AgentJobOrchestrator orchestrator = (AgentJobOrchestrator) getMediaArchive().getBean("agentJobOrchestrator");
		OpenCodeClient client = orchestrator.getOpenCodeClient();
		SessionStatus status = client.loadStatus(stepid);
		String requestid = step.get("pendingpermissionid");
		if (status == null || requestid == null)
		{
			throw new OpenEditException("OpenCodeApproveSkill: no opencode session waiting for step " + stepid);
		}
		if (!client.replyToPermission(status.getSessionId(), requestid, decision))
		{
			throw new OpenEditException("OpenCodeApproveSkill: opencode rejected the reply for step " + stepid);
		}

		step.setValue("status", "running");
		step.setValue("pendingquestion", null);
		step.setValue("pendingpermissionid", null);
		getMediaArchive().saveData("agentjobstep", step);

		LlmResponse response = new BasicLlmResponse();
		response.setMessage("Security prompt answered: " + decision);
		inContext.setLastResponse(response);
		super.process(inContext);
	}
}
