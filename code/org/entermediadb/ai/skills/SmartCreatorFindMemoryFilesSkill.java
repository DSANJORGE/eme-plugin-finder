package org.entermediadb.ai.skills;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.assistant.AssistantManager;
import org.entermediadb.ai.skills.SearchingSkill;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.json.simple.JSONArray;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.hittracker.HitTracker;

public class SmartCreatorFindMemoryFilesSkill extends BaseSkill
{

	public SearchingSkill getSearchingSkill()
	{
		SearchingSkill searchingManager = (SearchingSkill) getMediaArchive().getBean("searchingSkill");
		return searchingManager;
	}

	public ChatSmartCreatorConfirmationSkill getSmartCreatorSkill()
	{
		ChatSmartCreatorConfirmationSkill smartCreatorManager = (ChatSmartCreatorConfirmationSkill) getMediaArchive().getBean("smartCreatorSkill");
		return smartCreatorManager;
	}

	@Override
	public void process(AgentContext inContext)
	{
		findMemoryFiles(inContext);
		super.process(inContext);
	}

	public void runfunction(AgentContext inContext, MultiValued currentFunction)
	{
		String functionName = currentFunction.getId();
		if ("chat_smartcreator_findmemory".equals(functionName))
		{
			findMemoryFiles(inContext);
			BasicLlmResponse response = new BasicLlmResponse();
			response.setExecAutomationSkill("smartcreator_createoutline");
			inContext.setLastResponse(response);
		}
	}

	public void findMemoryFiles(AgentContext inContext)
	{
		Data module = inContext.getCurrentEntityModule();
		Data entity = inContext.getCurrentEntity();

		AssistantManager assistant = (AssistantManager) getMediaArchive().getBean("assistantManager");
		Collection<String> localparentIds = assistant.findDocIdsForEntity(module.getId(), entity.getId());
		// Set<String> parentIds = new HashSet();
		Set<String> finalparentIds = new HashSet<>();
		if (localparentIds != null)
		{
			finalparentIds.addAll(localparentIds);
		}

		Boolean skipcategorysearch = Boolean.valueOf(String.valueOf(inContext.getContextValue("skipcategorysearch")));

		if (!skipcategorysearch)
		{
			Collection<String> searchcats = entity.getValues("searchcategory");
			if (searchcats != null && !searchcats.isEmpty())
			{
				HitTracker modules = getMediaArchive().query("module").exact("semanticenabled", true).cachedSearch();
				Collection<String> moduleids = modules.collectValues("id");
				HitTracker addedentites = getMediaArchive().query("modulesearch")
					.addFacet("entitysourcetype")
					.put("searchtypes", moduleids)
					.includeDescription(true)
					.orgroup("searchcategory", searchcats)
					.exact("entityembeddingstatus", "embedded")
					.search();
				for (Iterator iterator = addedentites.iterator(); iterator.hasNext();)
				{
					Data doc = (Data) iterator.next();
					String type = doc.get("entitysourcetype");
					String docid = type + "_" + doc.getId();
					if (!finalparentIds.contains(docid))
					{
						finalparentIds.add(docid);
					}

				}

			}
		}
		if (finalparentIds.isEmpty())
		{
			inContext.error("Error state, No embeded Documents to Process, dont process more"); // Mark as error?
			return;
		}
		JSONArray array = new JSONArray();
		array.addAll(finalparentIds);
		inContext.getAiSmartCreatorSteps().setEmbeddedParentIds(array);

	}
}
