package org.entermediadb.ai.skills;

import java.util.Date;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.agentjobs.AgentJob;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.markdown.MarkdownUtil;
import org.openedit.OpenEditException;

public class AgentJobStatusSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(AgentJobStatusSkill.class);

	@Override
	public void process(AgentContext inContext)
	{
		log.info("AgentJobStatusSkill running for catalog: " + getCatalogId());
		AgentJob agentjob = (AgentJob) inContext.getContextValue("agentjob");
		if( agentjob == null)
		{
			log.warn("No agent job found in context.");
			return;
		}

		agentjob = (AgentJob)getMediaArchive().getCachedData("agentjob", agentjob.getId());
		inContext.put("agentjob", agentjob);
		MarkdownUtil markdown = new MarkdownUtil();
		inContext.put("markdown", markdown);


		Date endtime = agentjob.getDate("enddate");
		Date starttime = agentjob.getDate("submitteddate");
		if( endtime != null && starttime != null)
		{
			double secondsTaken = (endtime.getTime() - starttime.getTime()) / 5000D;
			//Only local. add putLocal
			inContext.getContext().put("secondstaken", secondsTaken);
		}

		LlmConnection llmconnection = getMediaArchive().getLlmConnection("localrender");
		LlmResponse response = llmconnection.renderLocalAction(inContext, "agent_job_showjobplan");

		//inContext.put("secondstaken", null);

		Long countloops = (Long) inContext.getContextValue("jobcountloops");

		if( countloops == null)
		{
			countloops = 0L;
		}
		else
		{
			countloops++;
		}
		inContext.put("jobcountloops", countloops);
		if( countloops > 500)
		{
			inContext.put("jobcountloops", 0);
			throw new OpenEditException("Agent job status skill has looped too many times. Something is wrong.");
		}
		String status = agentjob.get("status");

		//status = "complete";
		if (!"complete".equals(status) && !"error".equals(status))
		{
			log.info("Agent job not completed yet.");
			inContext.setWaitTime(5000L);
			response.setExecAutomationSkill("agentJobStatus");
		}	
		else
		{
			//complete or error, go back to the chat monitor
			String startup_scenario = (String) inContext.getContextValue("startup_scenario");
			response.setNextAutomationStep(startup_scenario);

		}
		inContext.setLastResponse(response);

		super.process(inContext);
	}
}
