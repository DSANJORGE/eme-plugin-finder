package org.entermediadb.ai.automation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.Skill;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.manager.BaseMediaObject;
import org.json.simple.JSONObject;
import org.openedit.CatalogEnabled;
import org.openedit.MultiValued;
import org.openedit.util.JSONParser;

public class RunningScenario extends BaseMediaObject implements CatalogEnabled
{
	private static final Log log = LogFactory.getLog(RunningScenario.class);

	//Collection<AutomationStep> fieldAgentsEnabled;

	public String fieldId;

	public String getId()
	{
		return fieldId;
	}

	public void setId(String inId)
	{
		fieldId = inId;
	}

	protected MultiValued fieldScenarioData;

	public void setScenarioData(MultiValued scenarioData)
	{
		fieldScenarioData = scenarioData;
	}

	public MultiValued getScenarioData()
	{
		if (fieldScenarioData == null)
		{
			MultiValued scenario = (MultiValued) getMediaArchive().getCachedData("automationscenario", getId());
			setScenarioData(scenario);
		}
		return fieldScenarioData;
	}

	public boolean runProcess(AutomationStep inSkillEnabled, AgentContext inContext)
	{
		return runProcess(inSkillEnabled, inContext, false);
	}

	public boolean runProcess(AutomationStep inSkillEnabled, AgentContext inContext, boolean skipStatusStart)
	{
		inContext.setCurrentAutomationStep(inSkillEnabled);
		Skill agent = inSkillEnabled.getAgent();
		if (agent == null)
		{
			log.error("No agent found for enabled " + inSkillEnabled.getEnabledId());
			return false;
		}
		if (!skipStatusStart)
		{
			inSkillEnabled.getAgent().startupScenario(inContext);
		}
		log.info("Running scenario: " + inContext.getCurrentScenario() + "  enabled skill: " + inSkillEnabled.getEnabledId());
		inSkillEnabled.getAgent().process(inContext);

		LlmResponse response = inContext.getLastResponse();
		if (response == null)
		{
			log.error("No response from " + inContext.getCurrentScenario() + " running " + inSkillEnabled.getEnabledId());
			return false;
		}

		if ("error".equals(response.getOperationState()))
		{
			log.error("Error from " + inContext.getCurrentScenario() + " running " + inSkillEnabled.getEnabledId() + ": " + response.getMessage());
			return false;
		}
		/// error, cancel, continue, runskill
		if ("cancel".equals(response.getOperationState()))
		{
			// Just return without broadcasting or saving anything. This is for when the function is called but
			// we determine we dont need to do anything.
			return false;
		}
		else if ("runskill".equals(response.getOperationState()))
		{
			String runskill = response.getExecAutomationSkill();
			runProcess(runskill, inContext);
			return false;
		}
		else if ("needuserinput".equals(response.getOperationState()))
		{
			// fire complete shoudl have sent it back to the user
			return false;
		}
		else
		{
			log.info("No status from " + inContext.getCurrentScenario() + " running " + inSkillEnabled.getEnabledId());
		}
		return true;
	}

	public boolean runProcess(String inFunctionParts, AgentContext inContext)
	{
		log.info("Running scenario: " + inFunctionParts );
		String[] parts = inFunctionParts.split("\\.");
		String stepid = null;
		RunningScenario runningScenario = this;
		if(parts.length > 1)
		{
			String scenario = parts[0];
			runningScenario = (RunningScenario) getMediaArchive().getBean("runningscenario", false);
			runningScenario.setId(scenario);
			inContext.setCurrentScenario(runningScenario);
			stepid = parts[1];
		}
		else
		{
			stepid = parts[0];	
		}
		// we are on a task, or answering questions or another sceneration.

		AutomationStep stepEnabled = runningScenario.findEnabled(stepid);
		if (stepEnabled == null)
		{
			log.error("No skill enabled found for id: " + stepid);
			return false;
		}
		AgentContext inCurrentContext = runningScenario.createAgentContext(inContext, stepEnabled);
		return runningScenario.runProcess(stepEnabled, inCurrentContext);
	}

	public AutomationStep findEnabled(String inEnabledId)
	{
		AutomationStep found = findEnabled(getEnabledAgents(), inEnabledId);
		return found;
	}

	public AutomationStep findEnabled(Collection<AutomationStep> agents, String inEnabledId)
	{
		for (AutomationStep enabled : agents)
		{
			if (enabled.getEnabledId().equals(inEnabledId))
			{
				return enabled;
			}
			AutomationStep found = findEnabled(enabled.getChildren(), inEnabledId);
			if (found != null)
			{
				return found;
			}
		}
		return null;
	}

	public Collection<AutomationStep> getEnabledAgents()
	{
		String inId = getId();

		getMediaArchive().getCacheManager().put("agentsenabled", inId, new ArrayList<AutomationStep>()); // Clear the cache to force reload

		Collection<AutomationStep> cached = (Collection<AutomationStep>) getMediaArchive().getCacheManager().get("agentsenabled", inId);

		if (cached == null || cached.isEmpty())
		{
			Collection found = getMediaArchive().query("automationstep").exact("automationscenario", inId).exact("enabled", true).search();
			Map<String, AutomationStep> allparents = new HashMap();
			for (Iterator iterator = found.iterator(); iterator.hasNext();)
			{
				MultiValued agentenableddata = (MultiValued) iterator.next();
				AutomationStep enabled = new AutomationStep();
				enabled.setAutomationStepData(agentenableddata);
				String agentid = agentenableddata.get("aiskill");
				MultiValued agentdata = (MultiValued) getMediaArchive().getCachedData("aiskill", agentid);
				enabled.setAgentData(agentdata);
				addContextValues(enabled);

				if (agentdata == null)
				{
					log.error("Could not find agent data for enabled agent " + agentenableddata.getId() + " with agentid " + agentid);
					continue;
				}

				String bean = agentdata.get("bean");
				if (bean == null)
				{
					log.error("No bean defined for agent " + agentenableddata.getId());
					continue;
				}
				Skill agent = loadAgent(bean);
				enabled.setAgent(agent);

				allparents.put(agentenableddata.getId(), enabled);
			}
			// Sort the list
			cached = new ArrayList();
			for (Iterator iterator = allparents.values().iterator(); iterator.hasNext();)
			{
				AutomationStep childAgent = (AutomationStep) iterator.next();
				String myparent = childAgent.getParentAgent();
				AutomationStep parentAgent = allparents.get(myparent);
				if (myparent == null || parentAgent == null)
				{
					cached.add(childAgent);
				}
				else
				{
					parentAgent.addChild(childAgent);
				}
			}
			getMediaArchive().getCacheManager().put("agentsenabled", inId, cached);
		}

		return cached;
	}

	public Skill loadAgent(String inName)
	{
		if (inName == null)
		{
			throw new IllegalArgumentException("Bean name not provided");
		}
		Skill Agent = (Skill) getMediaArchive().getCacheManager().get("ai", "Agent" + inName);
		if (Agent == null)
		{
			Agent = (Skill) getModuleManager().getBean(getCatalogId(), inName);
			getMediaArchive().getCacheManager().put("ai", "Agent" + inName, Agent);
		}
		return Agent;
	}

	public AgentContext createAgentContext(AutomationStep inEnabled)
	{
		return createAgentContext(null, inEnabled);
	}

	public AgentContext createAgentContext(AgentContext inParentContext, AutomationStep inEnabled)
	{
		String contextbeanname = inEnabled.getAgentData().get("contextbean");

		if (contextbeanname == null)
		{
			contextbeanname = "baseAgentContext";
		}
		AgentContext childContext = (AgentContext) getMediaArchive().getBean(contextbeanname, false);
		childContext.setCurrentAutomationStep(inEnabled);
		if (inParentContext != null)
		{
			childContext.setParentContext(inParentContext);
		}
		return childContext;
	}

	private void addContextValues(AutomationStep inAutomationStep)
	{
		MultiValued automationEnabledData = (MultiValued) inAutomationStep.getAutomationStepData();
		String text = automationEnabledData.get("contextvalues");
		if (text == null && inAutomationStep.getAgentData() != null)
		{
			text = inAutomationStep.getAgentData().get("contextvalues");
		}
		if (text != null)
		{
			JSONParser fieldJsonParser = new JSONParser();
			JSONObject json = (JSONObject) fieldJsonParser.parse(text);
			inAutomationStep.setExtraContextValues(json);
		}
	}
}
