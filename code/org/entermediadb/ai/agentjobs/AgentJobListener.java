package org.entermediadb.ai.agentjobs;

import org.openedit.Data;
import org.openedit.MultiValued;

public interface AgentJobListener
{

	public void finishedAllSteps(AgentJobRunnable inJob);

	public void finishedStep(AgentJobRunnable inJob, Data inStep);

	/** Called once the runnable exits, whether or not every step succeeded */
	public void finishedRun(AgentJobRunnable inJob);

	public void runStep(AgentJobRunnable agentJobRunnable, MultiValued step);
}
