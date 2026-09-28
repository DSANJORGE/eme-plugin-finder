package org.entermediadb.ai.skills;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.agentjobs.AgentJob;
import org.entermediadb.ai.agentjobs.AgentJobOrchestrator;
import org.openedit.MultiValued;

/**
 * OpenCodeUserRequestSkill - Turns the chat user's message into the input for OpenCodeRunnerSkill.
 *
 * Reads ChatMessageContext.getUserMessage(), creates an agentjob with one agentjobstep for
 * openCodeRunnerSkill, and puts "userrequest" and "agentjobstep" on the root context so the
 * runner step that runs after this one can read them. With no user message it stops and waits
 * for the next chat input.
 */
public class OpenCodeUserRequestSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(OpenCodeUserRequestSkill.class);

	@Override
	public void process(AgentContext inContext)
	{
		ChatMessageContext messageContext = (ChatMessageContext) inContext;
		MultiValued usermessage = messageContext.getUserMessage();
		if (usermessage == null)
		{
			return; //wait for the next input
		}
		String userrequest = usermessage.get("message");
		if (userrequest == null || userrequest.trim().isEmpty())
		{
			log.info("OpenCodeUserRequestSkill: empty user message " + usermessage.getId());
			return;
		}

		AgentJobOrchestrator orchestrator = (AgentJobOrchestrator) getMediaArchive().getBean("agentJobOrchestrator");
		AgentJob agentjob = orchestrator.createAgentJobFromMessage(usermessage, "openCodeRunnerSkill");
		MultiValued agentjobstep = agentjob.getSteps().iterator().next(); //one step

		// Child contexts read the root context, not their parent's, so share the values there
		inContext.putRoot("userrequest", userrequest);
		inContext.putRoot("agentjob", agentjob);
		inContext.putRoot("agentjobstep", agentjobstep);

		super.process(inContext);
	}
}
