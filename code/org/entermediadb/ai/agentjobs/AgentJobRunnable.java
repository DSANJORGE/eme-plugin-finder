package org.entermediadb.ai.agentjobs;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.openedit.Data;
import org.openedit.MultiValued;

public class AgentJobRunnable implements Runnable
{
	private static final Log log = LogFactory.getLog(AgentJobRunnable.class);

	AgentJob fieldAgentJob;
	AgentContext fieldContext;

	public AgentContext getContext() {
		return fieldContext;
	}
	public void setContext(AgentContext inContext) {
		fieldContext = inContext;
	}

	public AgentJob getAgentJob()
	{
		return fieldAgentJob;
	}

	public void setAgentJob(AgentJob fieldJobData) 
	{
		this.fieldAgentJob = fieldJobData;
	}

	Data fieldAgentJobRun;

	public Data getAgentJobRun()
	{
		return fieldAgentJobRun;
	}

	public void setAgentJobRun(Data inAgentJobRun)
	{
		fieldAgentJobRun = inAgentJobRun;
	}

	public String getId()
	{
		return getAgentJob().getId();
	}
	protected boolean fieldCompleted;
	protected AgentJobListener fieldJobListener;

	public AgentJobListener getEventListener()
	{
		return fieldJobListener;
	}

	public void setEventListener(AgentJobListener fieldJobListener)
	{
		this.fieldJobListener = fieldJobListener;
	}

	public boolean hasComplete()
	{
		return fieldCompleted;
	}

	public AgentJobRunnable() {}

	public void run()
	{
		try
		{
			for (MultiValued step : getAgentJob().getSteps())
			{
				//runner.run();
				//Get the job listenr and run it
				getEventListener().runStep(this, step);
				getEventListener().finishedStep(this,step);
			}
			fieldCompleted = true;
		}
		catch (Exception e)
		{
			log.error("ERRORS converting: " + getAgentJob(), e);
		}
		finally
		{
			if (hasComplete())
			{
				getEventListener().finishedAllSteps(this);
			}
			getEventListener().finishedRun(this);
		}
	}

}
