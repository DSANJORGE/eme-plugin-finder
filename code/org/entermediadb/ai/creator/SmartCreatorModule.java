package org.entermediadb.ai.creator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.entermediadb.ai.assistant.AssistantManager;
import org.entermediadb.ai.skills.QuestionsSkill;
import org.entermediadb.ai.skills.SearchingSkill;
import org.entermediadb.ai.skills.SmartCreatorPlaybackSkill;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.asset.modules.BaseMediaModule;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;

public class SmartCreatorModule extends BaseMediaModule
{

	public AssistantManager getAssistantManager(WebPageRequest inReq)
	{
		String catalogid = inReq.findValue("catalogid");
		AssistantManager assistantManager = (AssistantManager) getMediaArchive(catalogid).getBean("assistantManager");
		return assistantManager;
	}

	public SmartCreatorPlaybackSkill getSmartCreatorSkill(WebPageRequest inReq)
	{
		String catalogid = inReq.findValue("catalogid");
		SmartCreatorPlaybackSkill smartCreatorManager = (SmartCreatorPlaybackSkill) getMediaArchive(catalogid).getBean("smartCreatorPlaybackSkill");
		return smartCreatorManager;
	}

	public QuestionsSkill getQuestionsSkill(WebPageRequest inReq)
	{
		String catalogid = inReq.findValue("catalogid");
		QuestionsSkill questionsManager = (QuestionsSkill) getMediaArchive(catalogid).getBean("questionsSkill");
		return questionsManager;
	}

	public SearchingSkill getSearchingSkill(WebPageRequest inReq)
	{
		String catalogid = inReq.findValue("catalogid");
		SearchingSkill searchingManager = (SearchingSkill) getMediaArchive(catalogid).getBean("searchingSkill");
		return searchingManager;
	}

	public void loadTutorials(WebPageRequest inReq) throws Exception
	{
		Searcher tutorialsearcher = getMediaArchive(inReq).getSearcher("aitutorial");
		HitTracker hits = tutorialsearcher.query().exact("featured", true).search();

		inReq.putPageValue("tutorials", hits);
	}

	public void populateSection(WebPageRequest inReq) throws Exception
	{
		SmartCreatorPlaybackSkill creatorManager = getSmartCreatorSkill(inReq);
		// creatorManager.createCreatorAndPopulateSection(inReq);
	}

	public void loadCreator(WebPageRequest inReq) throws Exception
	{
		SmartCreatorPlaybackSkill creatorManager = getSmartCreatorSkill(inReq);
		creatorManager.getCreator(inReq);
	}

	public void createCreatorSection(WebPageRequest inReq) throws Exception
	{

		SmartCreatorPlaybackSkill creatorManager = getSmartCreatorSkill(inReq);

		Data playbackentity = (Data) inReq.getPageValue("currententity");
		Data playbackentitymodule = (Data) inReq.getPageValue("currententitymodule");

		if (playbackentity == null)
		{
			String playbackentityid = inReq.getRequestParameter("playbackentityid");
			String playbackentitymoduleid = inReq.getRequestParameter("playbackentitymoduleid");

			playbackentity = getMediaArchive(inReq).getCachedData(playbackentitymoduleid, playbackentityid);

			if (playbackentity == null)
			{
				throw new IllegalArgumentException("No creator entity found for id: " + playbackentityid);
			}

			playbackentitymodule = getMediaArchive(inReq).getCachedData("module", playbackentitymoduleid);
		}

		try
		{
			Map fields = new HashMap();
			String sectionid = inReq.getRequestParameter("sectionid");
			if (sectionid != null && sectionid.length() > 0)
			{
				fields.put("sectionid", sectionid);
				String name = inReq.getRequestParameter("name");
				fields.put("name", name);
			}
			else
			{
				String ordering = inReq.getRequestParameter("ordering");
				if (ordering != null && ordering.length() > 0)
				{
					int orderingint = Integer.parseInt(ordering);
					fields.put("ordering", orderingint + 1);
				}
				else
				{
					fields.put("ordering", 0);
				}
			}
			Data section = creatorManager.createCreatorSection(playbackentity, playbackentitymodule.getId(), fields);
			inReq.putPageValue("playbackentity", playbackentity);
			inReq.putPageValue("section", section);
		}
		catch (Exception ex)
		{
			throw new IllegalArgumentException("Ordering must be a number");
		}
	}

	public void deleteCreatorSection(WebPageRequest inReq) throws Exception
	{
		String searchtype = inReq.getRequestParameter("searchtype");
		String dataid = inReq.getRequestParameter("id");

		SmartCreatorPlaybackSkill creatorManager = getSmartCreatorSkill(inReq);
		creatorManager.deleteCreatorSection(searchtype, dataid);
	}

	public void saveComponentContent(WebPageRequest inReq) throws Exception
	{
		SmartCreatorPlaybackSkill creatorManager = getSmartCreatorSkill(inReq);
		String sectionid = inReq.getRequestParameter("sectionid");

		Map componentfields = new HashMap();

		String[] fields = inReq.getRequestParameters("field");

		Collection<String> fieldlist = new ArrayList<String>();

		if (fields != null)
		{
			for (int i = 0; i < fields.length; i++)
			{
				String fieldname = fields[i];
				if (fieldname != null && fieldname.length() > 0)
				{
					fieldlist.add(fieldname);
				}
			}
		}

		if (!fieldlist.contains("content"))
		{
			fieldlist.add("content");
		}
		if (!fieldlist.contains("componenttype"))
		{
			fieldlist.add("componenttype");
		}
		if (!fieldlist.contains("ordering"))
		{
			fieldlist.add("ordering");
		}
		if (!fieldlist.contains("componentcontentid"))
		{
			fieldlist.add("componentcontentid");
		}

		for (Iterator iterator = fieldlist.iterator(); iterator.hasNext();)
		{
			String fieldname = (String) iterator.next();

			String fieldvalue = inReq.getRequestParameter(fieldname + ".value");
			if (fieldvalue == null)
			{
				fieldvalue = inReq.getRequestParameter(fieldname + "value");
			}
			if (fieldvalue == null)
			{
				fieldvalue = inReq.getRequestParameter(fieldname);
			}
			if (fieldvalue == null || fieldvalue.length() == 0)
			{
				continue;
			}
			componentfields.put(fieldname, fieldvalue);
		}

		Data componentcontent = creatorManager.saveComponentContent(sectionid, componentfields);
		inReq.putPageValue("componentcontent", componentcontent);

		if (componentfields.get("componentcontent") == null)
		{
			inReq.putPageValue("newcontent", true);
		}
	}

	public void saveContentRole(WebPageRequest inReq) throws Exception
	{
		MediaArchive archive = getMediaArchive(inReq);
		String searchtype = inReq.getRequestParameter("searchtype");

		String sectionid = inReq.getRequestParameter("sectionid");

		Searcher searcher = archive.getSearcher(searchtype);

		String id = inReq.getRequestParameter("id");
		Data data = searcher.loadData(id);
		if (data != null)
		{
			String contentrole = inReq.getRequestParameter("contentrole");
			data.setValue("contentrole", contentrole);
			searcher.saveData(data);
		}

		inReq.putPageValue(searchtype, data);

		if ("componentsection".equals(searchtype))
		{
			inReq.putPageValue("section", data);
		}
		else
		{
			Data section = archive.query("componentsection").exact("id", sectionid).searchOne();
			inReq.putPageValue("section", section);
		}

		inReq.putPageValue("searchtype", searchtype);
	}

	public void populateComponentContent(WebPageRequest inReq) throws Exception
	{
		SmartCreatorPlaybackSkill creatorManager = getSmartCreatorSkill(inReq);
		// creatorManager.autoPopulateComponentContent();
	}

	public void orderCreatorSection(WebPageRequest inReq)
	{
		SmartCreatorPlaybackSkill creatorManager = getSmartCreatorSkill(inReq);
		creatorManager.orderCreatorSection(inReq);
	}

	public void duplicateCreatorSection(WebPageRequest inReq)
	{
		String searchtype = inReq.getRequestParameter("searchtype");
		String dataid = inReq.getRequestParameter("id");

		SmartCreatorPlaybackSkill creatorManager = getSmartCreatorSkill(inReq);
		Data duplicate = creatorManager.duplicateCreatorSection(searchtype, dataid);

		inReq.putPageValue("searchtype", searchtype);

		if ("componentcontent".equals(searchtype))
		{
			inReq.putPageValue("componentcontent", duplicate);
		}
		else if ("componentsection".equals(searchtype))
		{
			inReq.putPageValue("section", duplicate);
		}
	}

	public void creatorAiAction(WebPageRequest inReq)
	{
		String aiaction = inReq.getRequestParameter("aiaction");
		String componentcontentid = inReq.getRequestParameter("componentcontentid");

		SmartCreatorPlaybackSkill creatorManager = (SmartCreatorPlaybackSkill) getMediaArchive(inReq).getBean("smartCreatorPlaybackSkill");

		if ("grammar".equals(aiaction))
		{
			creatorManager.correctGrammar(inReq, componentcontentid);
		}
		else if ("improve".equals(aiaction))
		{
			creatorManager.improveContent(inReq, componentcontentid);
		}
		else if ("generate".equals(aiaction))
		{
			String prompt = inReq.getRequestParameter("aiprompt");
			creatorManager.generateContent(inReq, componentcontentid, prompt);
		}
		else if ("image".equals(aiaction))
		{
			String prompt = inReq.getRequestParameter("aiprompt");
			creatorManager.createImage(inReq, componentcontentid, prompt);
		}
		else if ("caption".equals(aiaction))
		{
			String assetid = inReq.getRequestParameter("assetid");
			creatorManager.captionImage(inReq, componentcontentid, assetid);
		}
	}

}
