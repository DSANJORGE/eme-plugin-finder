package org.entermediadb.ai.agentjobs;

import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.Skill;
import org.entermediadb.ai.automation.AutomationManager;
import org.entermediadb.ai.llm.BaseAgentContext;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.scripts.LogListener;
import org.openedit.CatalogEnabled;
import org.openedit.Data;
import org.openedit.ModuleManager;
import org.openedit.MultiValued;
import org.openedit.data.QueryBuilder;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;
import org.openedit.util.ExecutorManager;
import org.openedit.util.JSONParser;

public class AgentJobOrchestrator implements AgentJobListener, CatalogEnabled
{
	private static final Log log = LogFactory.getLog(AgentJobOrchestrator.class);
	
	protected MediaArchive fieldMediaArchive;
	protected ModuleManager fieldModuleManager;
	protected Map currentJobsRunning = new ConcurrentHashMap();
	protected String fieldCatalogId;
	protected int fieldTotalPending;

	public int getMaxProcessors()
	{
		if (fieldMaxProcessors == -1)
		{
			String max = getMediaArchive().getCatalogSettingValue("conversion_max_processors");
			if (max != null)
			{
				fieldMaxProcessors = Integer.parseInt(max);
			}
			else
			{
				fieldMaxProcessors = getThreads().getAvailableProcessors() - 1;
			}
			if (fieldMaxProcessors < 1)
			{
				fieldMaxProcessors = 1;
			}
		}
		return fieldMaxProcessors;
	}

	public void setMaxProcessors(int inMaxProcessors)
	{
		fieldMaxProcessors = inMaxProcessors;
	}

	protected int fieldMaxProcessors = -1;

	public int getTotalPending()
	{
		return fieldTotalPending;
	}

	public void setTotalPending(int inTotalPending)
	{
		fieldTotalPending = inTotalPending;
	}

	public String getCatalogId()
	{
		return fieldCatalogId;
	}

	public void setCatalogId(String inCatalogId)
	{

		fieldCatalogId = inCatalogId;
	}

	public ModuleManager getModuleManager()
	{
		return fieldModuleManager;
	}

	public void setModuleManager(ModuleManager inModuleManager)
	{
		fieldModuleManager = inModuleManager;
	}

	public MediaArchive getMediaArchive()
	{
		if (fieldMediaArchive == null)
		{
			fieldMediaArchive = (MediaArchive) getModuleManager().getBean(getCatalogId(), "mediaArchive");
		}
		return fieldMediaArchive;
	}

	public void setMediaArchive(MediaArchive inMediaArchive)
	{
		fieldMediaArchive = inMediaArchive;
	}

	public synchronized void checkQueue()
	{
		if (!hasAvailableProcessor())
		{
			log.info("No available processors");
			return;
		}

		// Lock searching for tasks
		try
		{
			HitTracker newjobs = getNewjobs();
			setTotalPending(newjobs.size());
			if (newjobs.size() > 0)
			{
				log.info("processing " + newjobs.size() + " AgentJob with statuses: new submitted retry missinginput");
			}
			else
			{
				return;
			}
			for (Iterator iterator = newjobs.iterator(); iterator.hasNext();)
			{
				// lock and create
				if (currentJobsRunning.size() >= availableProcessors())
				{
					break;
				}
				Data hit = (Data) iterator.next();

				AgentJob job = (AgentJob)getMediaArchive().getCachedData("agentjob", hit.getId());
				if( job.getValue("status") != null && job.getValue("status").equals("running"))
				{
					log.info("Skipping job " + job + " as it is already running");
					continue;
				}

				job.setValue("status","running");
				getMediaArchive().saveData("agentjob", job);

				//Run it now
				AgentJobRunnable torun = new AgentJobRunnable();
				AgentContext context = new BaseAgentContext();
				context.setCatalogId(getCatalogId());
				context.setModuleManager(getModuleManager());
				context.put("agentjob", job);
				//context.put("userrequest", job.get("userrequest"));
				torun.setContext(context);
				torun.setAgentJob(job);
				torun.setEventListener(this);
				addAgentJob(torun);
			}
		}
		catch (Throwable ex)
		{
			log.error("Could not process queue ", ex);
		}
	}

	public HitTracker getNewjobs()
	{
		Searcher jobsearcher = getMediaArchive().getSearcher("agentjob");

		QueryBuilder query = getMediaArchive().localQuery("agentjob");
		query.orgroup("status", "new"); 
		query.sort("submitteddateDown");

		if (hasRunningAgentJob()) //Skip these just in case
		{
			query.notgroup("id", getRunningIds());
		}
		HitTracker newjobs = jobsearcher.search(query.getQuery());
		newjobs.enableBulkOperations();
		newjobs.setHitsPerPage(500); // Just enought to fill up the queue
		return newjobs;
	}

	public Map getCurrentJobsRunning()
	{
		return currentJobsRunning;
	}

	private boolean hasRunningAgentJob()
	{
		return currentJobsRunning.isEmpty() == false;
	}

	public Set getRunningIds()
	{
		return currentJobsRunning.keySet();
	}

	private boolean hasAvailableProcessor()
	{
		return availableProcessors() > 0;
	}

	private int availableProcessors()
	{
		int total = getMaxProcessors();
		total = total - currentJobsRunning.size();
		return total;
	}

	public int runningProcesses()
	{
		return currentJobsRunning.size();
	}

	private void addAgentJob(AgentJobRunnable inAgentJob)
	{
		if (currentJobsRunning.size() >= availableProcessors())
		{
			//Should we save?
			return;
		}

		currentJobsRunning.put(inAgentJob.getId(), inAgentJob);
		getThreads().execute("importing", inAgentJob);
	}

	public void runStep(AgentJobRunnable inAgentJob, MultiValued inStep)
	{
		try
		{
			String aiskillid = inStep.get("aiskillid");
			if( aiskillid != null )
			{
				runSkill(inAgentJob,inStep);
			}
			
			String workflowid = inStep.get("workflowid");
			if( workflowid != null )
			{
				runWorkflow(inAgentJob,inStep);
			}	
		}
		catch (Exception ex)
		{
			log.error("Error running step " + inStep.getId(), ex);
			inStep.setValue("status", "error");
			inStep.setValue("errordetails", ex.getMessage());
			inAgentJob.getAgentJob().setValue("status", "error");
			getMediaArchive().saveData("agentjob", inAgentJob.getAgentJob());
			throw new RuntimeException(ex); //stop processing the rest of the steps
		}
		finally
		{
			getMediaArchive().saveData("agentjobstep", inStep);
		}
	}

	private void runSkill( AgentJobRunnable inAgentJob, MultiValued inStep)
	{
		String aiskillid = inStep.get("aiskillid");
		Data aiskill = getMediaArchive().query("aiskill").exact("id", aiskillid).searchOne();
		inStep.setValue("status", "running");
		getMediaArchive().saveData("agentjobstep", inStep);

		inAgentJob.getContext().put("agentjobstep",inStep);

		String userrequest = inStep.get("markdowncontent"); //Starting point for each job
		inAgentJob.getContext().put("userrequest", userrequest);

			Skill skill = (Skill) getModuleManager().getBean(getCatalogId(), aiskill.get("bean"));
			String json = inStep.get("parameters");
			Collection<Map<String,Object>> parameters = null;
			if( json != null)
			{
				parameters = (Collection<Map<String,Object>>)new JSONParser().parseCollection(json);
			}

			if(  parameters != null)
			{
				for (Map<String,Object> map : parameters) 
				{
					String key = (String)map.get("input_id");
					String value = (String)map.get("value");
					if( value == null)
					{
						String oldkey  = (String)map.get("variable");
						if( oldkey == null)
						{
							oldkey = key;
						}
						value = (String)inAgentJob.getContext().getContextValue(oldkey);
					}
					if( value == null)
					{
						continue;
					}
					if( value.startsWith("${"))
					{
						String[] parts = value.split("\\.");
						if( parts.length > 1)
						{
							String oldkey = parts[parts.length -1];
							if( oldkey.endsWith("}"))
							{
								oldkey = oldkey.substring(0,oldkey.length() -1);
							}
							value = (String)inAgentJob.getContext().getContextValue(oldkey);
						}
					}

					inAgentJob.getContext().put(key,value);
				}
			}

			skill.process(inAgentJob.getContext());


			// if( inAgentJob.getContext().getLastResponse() != null)
			// {
			// 	String message = inAgentJob.getContext().getLastResponse().getMessage();
			// 	inStep.setValue("lastresponse", message);
			// }

			inStep.setValue("status", "complete");
			getMediaArchive().saveData("agentjobstep", inStep);
	}

	AutomationManager fieldAutomationManager;
	protected AutomationManager getAutomationManager()
	{
		if( fieldAutomationManager == null)
		{
			fieldAutomationManager = (AutomationManager) getModuleManager().getBean(getCatalogId(), "automationManager");
		}
		return fieldAutomationManager;
	}

	private void runWorkflow(AgentJobRunnable inAgentJob, MultiValued inStep)
	{
		String workflowid = inStep.get("workflowid");
		inStep.setValue("status", "running");
		getMediaArchive().saveData("agentjobstep", inStep);
		try
		{
			AgentContext context = new BaseAgentContext();
			context.setCatalogId(getCatalogId());
			context.setModuleManager(getModuleManager());
			context.put("agentjob", inAgentJob.getAgentJob());
			context.put("agentjobstep", inStep);

			getAutomationManager().runScenario(workflowid, context);

			inStep.setValue("status", "complete");
		}
		catch (Exception ex)
		{
			log.error("Error running step " + inStep.getId(), ex);
			inStep.setValue("status", "error");
			inStep.setValue("errordetails", ex.getMessage());
		}
		finally
		{
			getMediaArchive().saveData("agentjobstep", inStep);
		}

	}

	public void finishedStep(AgentJobRunnable inAgentJob, Data inStep)
	{
		//currentJobsRunning.remove(inAgentJob.getId());

		//Do nothing

	}

	public void finishedAllSteps(AgentJobRunnable inAgentJob)
	{
		try
		{
			currentJobsRunning.remove(inAgentJob.getId());
			log.info("RELEASED " + inAgentJob.getId());
			//Save job
			//Do we have this last response from the steps?
			//String lastresponse = inAgentJob.getAgentJob().findLastResponse();
			//inAgentJob.getAgentJob().setValue("lastresponse", lastresponse);
			
			inAgentJob.getAgentJob().setValue("status", "complete");
			inAgentJob.getAgentJob().setValue("enddate", new Date());
			getMediaArchive().saveData("agentjob", inAgentJob.getAgentJob());

			checkQueue();
		}
		catch (Exception ex)
		{
			log.error("Problem finishing AgentJob ", ex);
		}
	}



	public ExecutorManager getThreads()
	{
		ExecutorManager queue = (ExecutorManager) getModuleManager().getBean(getMediaArchive().getCatalogId(), "executorManager");
		return queue;
	}


}
