package org.entermediadb.topic;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.asset.modules.BaseMediaModule;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.OpenEditException;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.util.DateStorageUtil;

public class TopicManager extends BaseMediaModule
{

	public void getUserTopics(WebPageRequest inReq)
	{
		MediaArchive mediaArchive = getMediaArchive(inReq);

		Collection<MultiValued> topics = mediaArchive.query("entitytopic").all().search(inReq);
		if (topics == null)
		{
			topics = Collections.emptyList();
		}

		String userid = inReq.getUser() != null ? inReq.getUser().getId() : null;
		Set<String> completedQuestionIds = new HashSet<>();
		if (userid != null)
		{
			Collection<Data> answers = mediaArchive.query("tutoranswer").exact("user", userid).search(inReq);
			if (answers != null)
			{
				for (Data answer : answers)
				{
					String qid = answer.get("entityquestion");
					if (qid != null && !qid.trim().isEmpty())
					{
						completedQuestionIds.add(qid.trim());
					}
				}
			}
		}

		int questionspersection = 1;
		String qpsStr = mediaArchive.getCatalogSettingValue("questions-per-section");
		if (qpsStr != null && !qpsStr.trim().isEmpty())
		{
			try
			{
				questionspersection = Integer.parseInt(qpsStr.trim());
			}
			catch (Exception e)
			{
				questionspersection = 1;
			}
		}

		List<String> topicIds = new ArrayList<>();
		for (MultiValued topic : topics)
		{
			if (topic.getId() != null)
			{
				topicIds.add(topic.getId());
			}
		}

		Collection<MultiValued> allTutorials = null;
		if (!topicIds.isEmpty())
		{
			allTutorials = mediaArchive.query("entitytutorial").orgroup("entitytopic", topicIds).search(inReq);
		}
		if (allTutorials == null)
		{
			allTutorials = Collections.emptyList();
		}

		Map<String, List<MultiValued>> tutorialsByTopicId = new HashMap<>();
		List<String> tutorialIds = new ArrayList<>();
		for (MultiValued tutorial : allTutorials)
		{
			String topicId = tutorial.get("entitytopic");
			if (topicId != null)
			{
				tutorialsByTopicId.computeIfAbsent(topicId, k -> new ArrayList<>()).add(tutorial);
			}
			if (tutorial.getId() != null)
			{
				tutorialIds.add(tutorial.getId());
			}
		}

		Collection<MultiValued> allSections = null;
		if (!tutorialIds.isEmpty())
		{
			allSections = mediaArchive.query("componentsection").orgroup("playbackentityid", tutorialIds).exact("playbackentitymoduleid", "entitytutorial").sort("ordering").search(inReq);
		}
		if (allSections == null)
		{
			allSections = Collections.emptyList();
		}

		Map<String, List<MultiValued>> sectionsByTutorialId = new HashMap<>();
		List<String> sectionIds = new ArrayList<>();
		for (MultiValued section : allSections)
		{
			String tutId = section.get("playbackentityid");
			if (tutId != null)
			{
				sectionsByTutorialId.computeIfAbsent(tutId, k -> new ArrayList<>()).add(section);
			}
			if (section.getId() != null)
			{
				sectionIds.add(section.getId());
			}
		}

		Collection<MultiValued> allComponents = null;
		if (!sectionIds.isEmpty())
		{
			allComponents = mediaArchive.query("componentcontent").orgroup("componentsectionid", sectionIds).search(inReq);
		}
		if (allComponents == null)
		{
			allComponents = Collections.emptyList();
		}

		Map<String, List<MultiValued>> componentsBySectionId = new HashMap<>();
		for (MultiValued component : allComponents)
		{
			String secId = component.get("componentsectionid");
			if (secId != null)
			{
				componentsBySectionId.computeIfAbsent(secId, k -> new ArrayList<>()).add(component);
			}
		}

		Map<String, MultiValued> progressByTutorialId = new HashMap<>();
		if (userid != null && !tutorialIds.isEmpty())
		{
			Collection<MultiValued> allProgress = mediaArchive.query("tutorialprogress").orgroup("entitytutorial", tutorialIds).exact("user", userid).search(inReq);
			if (allProgress != null)
			{
				for (MultiValued p : allProgress)
				{
					String tutId = p.get("entitytutorial");
					if (tutId != null)
					{
						progressByTutorialId.put(tutId, p);
					}
				}
			}
		}

		Collection<Map<String, Object>> data = new ArrayList<>();
		int absoluteTotal = 0;
		int absoluteCompleted = 0;

		for (MultiValued topic : topics)
		{
			Map<String, Object> topicMap = new HashMap<>();
			topicMap.put("topic", topic);

			List<MultiValued> topicTutorials = tutorialsByTopicId.get(topic.getId());
			if (topicTutorials == null)
			{
				topicTutorials = Collections.emptyList();
			}

			List<Map<String, Object>> tutorialList = new ArrayList<>();
			int topicTotalSections = 0;
			int topicCompletedSections = 0;
			double beginnerProgressSum = 0.0;
			double competentProgressSum = 0.0;
			double expertProgressSum = 0.0;

			for (MultiValued tutorial : topicTutorials)
			{
				Map<String, Object> tutorialMap = new HashMap<>();
				tutorialMap.put("tutorial", tutorial);

				List<MultiValued> sections = sectionsByTutorialId.get(tutorial.getId());
				if (sections == null)
				{
					sections = Collections.emptyList();
				}

				int tutTotalSections = sections.size();
				int tutCompletedSections = 0;
				for (MultiValued section : sections)
				{
					if (isSectionCompleted(section.getId(), componentsBySectionId, completedQuestionIds, questionspersection))
					{
						tutCompletedSections++;
					}
				}

				MultiValued progress = progressByTutorialId.get(tutorial.getId());
				Map<String, Double> progressMap = createProgressMap(progress);

				tutorialMap.put("totalsections", tutTotalSections);
				tutorialMap.put("completedsections", tutCompletedSections);
				tutorialMap.put("progress", progressMap);

				tutorialList.add(tutorialMap);

				topicTotalSections += tutTotalSections;
				topicCompletedSections += tutCompletedSections;

				beginnerProgressSum += progressMap.get("beginnerprogress");
				competentProgressSum += progressMap.get("competentprogress");
				expertProgressSum += progressMap.get("expertprogress");
			}

			Map<String, Double> topicProgressMap = new HashMap<>();
			if (topicTutorials.isEmpty())
			{
				topicProgressMap.put("beginnerprogress", 0.0);
				topicProgressMap.put("competentprogress", 0.0);
				topicProgressMap.put("expertprogress", 0.0);
			}
			else
			{
				topicProgressMap.put("beginnerprogress", beginnerProgressSum / topicTutorials.size());
				topicProgressMap.put("competentprogress", competentProgressSum / topicTutorials.size());
				topicProgressMap.put("expertprogress", expertProgressSum / topicTutorials.size());
			}

			topicMap.put("progress", topicProgressMap);
			topicMap.put("totalsections", topicTotalSections);
			topicMap.put("completedsections", topicCompletedSections);
			topicMap.put("tutorials", topicTutorials.size());
			topicMap.put("tutoriallist", tutorialList);
			topicMap.put("tutorialsdata", tutorialList);

			data.add(topicMap);
			absoluteTotal += topicTotalSections;
			absoluteCompleted += topicCompletedSections;
		}

		inReq.putPageValue("data", data);
		inReq.putPageValue("absolutetotal", absoluteTotal);
		inReq.putPageValue("absolutecompleted", absoluteCompleted);
	}

	public boolean isSectionCompleted(String sectionId, Map<String, List<MultiValued>> componentsBySectionId, Set<String> completedQuestionIds, int questionspersection)
	{
		List<MultiValued> components = componentsBySectionId.get(sectionId);
		if (components == null || components.isEmpty())
		{
			return false;
		}
		int totalQuestions = 0;
		int answeredQuestions = 0;
		for (MultiValued comp : components)
		{
			String qid = comp.get("questionid");
			if (qid != null && !qid.trim().isEmpty())
			{
				totalQuestions++;
				if (completedQuestionIds.contains(qid.trim()))
				{
					answeredQuestions++;
				}
			}
		}
		if (totalQuestions == 0)
		{
			return false;
		}
		int required = Math.min(questionspersection, totalQuestions);
		return answeredQuestions >= required;
	}

	public void getTopicTutorials(WebPageRequest inReq)
	{
		String topicid = inReq.getRequestParameter("entitytopic");
		MediaArchive mediaArchive = getMediaArchive(inReq);

		Collection<MultiValued> alltutorials = mediaArchive.query("entitytutorial").exact("entitytopic", topicid).search(inReq);
		if (alltutorials == null)
		{
			alltutorials = Collections.emptyList();
		}

		String userid = inReq.getUser() != null ? inReq.getUser().getId() : null;
		Set<String> completedQuestionIds = new HashSet<>();
		if (userid != null)
		{
			Collection<Data> answers = mediaArchive.query("tutoranswer").exact("user", userid).search(inReq);
			if (answers != null)
			{
				for (Data a : answers)
				{
					String qid = a.get("entityquestion");
					if (qid != null && !qid.trim().isEmpty())
					{
						completedQuestionIds.add(qid.trim());
					}
				}
			}
		}

		int questionspersection = 1;
		String qpsStr = mediaArchive.getCatalogSettingValue("questions-per-section");
		if (qpsStr != null && !qpsStr.trim().isEmpty())
		{
			try
			{
				questionspersection = Integer.parseInt(qpsStr.trim());
			}
			catch (Exception e)
			{
				questionspersection = 1;
			}
		}

		List<String> tutorialIds = new ArrayList<>();
		for (MultiValued t : alltutorials)
		{
			if (t.getId() != null)
			{
				tutorialIds.add(t.getId());
			}
		}

		Collection<MultiValued> allSections = null;
		if (!tutorialIds.isEmpty())
		{
			allSections = mediaArchive.query("componentsection").orgroup("playbackentityid", tutorialIds).exact("playbackentitymoduleid", "entitytutorial").sort("ordering").search(inReq);
		}
		if (allSections == null)
		{
			allSections = Collections.emptyList();
		}

		Map<String, List<MultiValued>> sectionsByTutorialId = new HashMap<>();
		List<String> sectionIds = new ArrayList<>();
		for (MultiValued section : allSections)
		{
			String tutId = section.get("playbackentityid");
			if (tutId != null)
			{
				sectionsByTutorialId.computeIfAbsent(tutId, k -> new ArrayList<>()).add(section);
			}
			if (section.getId() != null)
			{
				sectionIds.add(section.getId());
			}
		}

		Collection<MultiValued> allComponents = null;
		if (!sectionIds.isEmpty())
		{
			allComponents = mediaArchive.query("componentcontent").orgroup("componentsectionid", sectionIds).search(inReq);
		}
		if (allComponents == null)
		{
			allComponents = Collections.emptyList();
		}

		Map<String, List<MultiValued>> componentsBySectionId = new HashMap<>();
		for (MultiValued component : allComponents)
		{
			String secId = component.get("componentsectionid");
			if (secId != null)
			{
				componentsBySectionId.computeIfAbsent(secId, k -> new ArrayList<>()).add(component);
			}
		}

		Map<String, MultiValued> progressByTutorialId = new HashMap<>();
		if (userid != null && !tutorialIds.isEmpty())
		{
			Collection<MultiValued> allProgress = mediaArchive.query("tutorialprogress").orgroup("entitytutorial", tutorialIds).exact("user", userid).search(inReq);
			if (allProgress != null)
			{
				for (MultiValued p : allProgress)
				{
					String tutId = p.get("entitytutorial");
					if (tutId != null)
					{
						progressByTutorialId.put(tutId, p);
					}
				}
			}
		}

		Collection<Map<String, Object>> data = new ArrayList<>();

		for (MultiValued tutorial : alltutorials)
		{
			Map<String, Object> tutorialmap = new HashMap<>();
			tutorialmap.put("tutorial", tutorial);

			MultiValued progress = progressByTutorialId.get(tutorial.getId());
			Map<String, Double> progressMap = createProgressMap(progress);
			tutorialmap.put("progress", progressMap);

			List<MultiValued> sections = sectionsByTutorialId.get(tutorial.getId());
			if (sections == null)
			{
				sections = Collections.emptyList();
			}

			int totalSections = sections.size();
			int completedSections = 0;
			for (MultiValued section : sections)
			{
				if (isSectionCompleted(section.getId(), componentsBySectionId, completedQuestionIds, questionspersection))
				{
					completedSections++;
				}
			}

			tutorialmap.put("totalsections", totalSections);
			tutorialmap.put("completedsections", completedSections);
			data.add(tutorialmap);
		}
		inReq.putPageValue("data", data);
	}

	public void loadTutorial(WebPageRequest inReq)
	{
		String tutorialid = inReq.getRequestParameter("entitytutorial");
		MediaArchive mediaArchive = getMediaArchive(inReq);

		MultiValued tutorial = (MultiValued) mediaArchive.query("entitytutorial").exact("id", tutorialid).searchOne();
		inReq.putPageValue("tutorial", tutorial);

		MultiValued progress = (MultiValued) mediaArchive.query("tutorialprogress").exact("entitytutorial", tutorial.getId()).exact("user", inReq.getUser().getId()).searchOne();
		Map<String, Double> progressMap = createProgressMap(progress);
		inReq.putPageValue("progress", progressMap);

		Collection<MultiValued> sections =
			mediaArchive.query("componentsection").exact("playbackentitymoduleid", "entitytutorial").exact("playbackentityid", tutorial.getId()).sort("ordering").search();

		inReq.putPageValue("sections", sections);
	}

	private Map<String, Double> createProgressMap(MultiValued progress)
	{
		Map<String, Double> progressMap = new HashMap<>();
		if (progress == null)
		{
			progressMap.put("beginnerprogress", 0.0);
			progressMap.put("competentprogress", 0.0);
			progressMap.put("expertprogress", 0.0);
			return progressMap;
		}
		Double bp = progress.getDouble("beginnerprogress");
		if (bp == null)
		{
			bp = 0.0;
		}
		Double cp = progress.getDouble("competentprogress");
		if (cp == null)
		{
			cp = 0.0;
		}
		Double ep = progress.getDouble("expertprogress");
		if (ep == null)
		{
			ep = 0.0;
		}
		progressMap.put("beginnerprogress", bp);
		progressMap.put("competentprogress", cp);
		progressMap.put("expertprogress", ep);
		return progressMap;
	}

	public void loadTutorialHistory(WebPageRequest inReq)
	{
		MediaArchive mediaArchive = getMediaArchive(inReq);
		MultiValued currentchannel;
		MultiValued activechannel;
		String dataid = inReq.getRequestParameter("dataid");
		String channelid = inReq.getRequestParameter("channel");
		if (channelid != null)
		{
			currentchannel = (MultiValued) mediaArchive.query("channel").exact("searchtype", "entitytutorial").id(channelid).exact("user", inReq.getUser().getId()).searchOne();
			if (currentchannel == null)
			{
				throw new OpenEditException("Channel not found: " + channelid + " for user " + inReq.getUser().getId());
			}
			if ("finished".equals(currentchannel.get("channelstatus")))
			{
				activechannel = (MultiValued) mediaArchive.query("channel").exact("searchtype", "entitytutorial").exact("dataid", dataid).not("channelstatus", "finished").sort("dateDown").searchOne();
			}
			else
			{
				activechannel = currentchannel;
			}
		}
		else
		{
			activechannel = (MultiValued) mediaArchive.query("channel")
				.exact("searchtype", "entitytutorial")
				.exact("dataid", dataid)
				.exact("user", inReq.getUser())
				.not("channelstatus", "finished")
				.sort("dateDown")
				.searchOne();
			if (activechannel == null)
			{
				throw new OpenEditException("Channel not found for user " + inReq.getUser().getId());
			}
			currentchannel = activechannel;
		}
		inReq.putPageValue("currentchannel", currentchannel);
		inReq.putPageValue("activechannel", activechannel);

		Collection<MultiValued> history = mediaArchive.query("channel")
			.exact("searchtype", "entitytutorial")
			.exact("dataid", dataid)
			.exact("user", inReq.getUser().getId())
			.exact("channelstatus", "finished")
			.sort("dateDown")
			.search();
		inReq.putPageValue("channelhistory", history);

		Collection<MultiValued> messages = mediaArchive.query("chatterbox").exact("channel", currentchannel.getId()).orgroup("user", inReq.getUser().getId() + ",agent").sort("dateUp").search();
		inReq.putPageValue("messages", messages);

		Collection<MultiValued> answers = mediaArchive.query("tutoranswer").exact("channel", currentchannel.getId()).exact("user", inReq.getUser().getId()).sort("dateUp").search();
		inReq.putPageValue("answers", answers);
	}

	public void loadDailyChallenge(WebPageRequest inReq)
	{
		MediaArchive mediaArchive = getMediaArchive(inReq);
		Searcher dailychallengeSearcher = mediaArchive.getSearcher("tutordailychallenge");
		Searcher channelSearcher = mediaArchive.getSearcher("channel");
		Data currentchannel = null;

		Date today = DateStorageUtil.getStorageUtil().getToday();
		String dateStr = inReq.getRequestParameter("date");
		Date date = today;
		if (dateStr != null)
		{
			date = DateStorageUtil.getStorageUtil().truncateDate(DateStorageUtil.getStorageUtil().parseFromStorage(dateStr));
		}

		Data dailychallenge = dailychallengeSearcher.query().exact("challengedate", date).searchOne();

		if (dailychallenge != null)
		{
			currentchannel = channelSearcher.query().id(dailychallenge.get("channel")).searchOne();
		}
		else
		{
			if (date.equals(today))
			{
				currentchannel = channelSearcher.createNewData();
				currentchannel.setName("Daily Challenge: " + dateStr);
				currentchannel.setValue("searchtype", "tutordailychallenge");
				currentchannel.setValue("channeltype", "agenttutorchat");
				currentchannel.setValue("user", inReq.getUser().getId());
				currentchannel.setValue("dataid", dateStr);

				Calendar now = Calendar.getInstance();
				now.add(Calendar.SECOND, -1);
				currentchannel.setValue("refreshdate", now.getTime());

				channelSearcher.saveData(currentchannel);

				inReq.putPageValue("currentchannel", currentchannel);

				Collection<Data> answeredQuestions = mediaArchive.query("tutoranswer").exact("user", inReq.getUser().getId()).sort("datecreatedDown").search();
				List<String> answeredQuestionIds = answeredQuestions.stream().map(d -> d.get("entityquestion")).collect(Collectors.toList());

				Collection<Data> unAnsweredQuestions = mediaArchive.query("entityquestion").notgroup("id", answeredQuestionIds).search();
				Collection<String> unAnsweredQuestionIds = unAnsweredQuestions.stream().map(d -> d.getId()).collect(Collectors.toList());

				if (unAnsweredQuestionIds.isEmpty() && !answeredQuestionIds.isEmpty())
				{
					unAnsweredQuestionIds.add(answeredQuestionIds.get(0));
				}

				Data questionComponent = mediaArchive.query("componentcontent").orgroup("questionid", unAnsweredQuestionIds).searchOne();

				String sectionId = questionComponent.get("componentsectionid");

				dailychallenge = dailychallengeSearcher.createNewData();
				dailychallenge.setValue("challengedate", today);
				dailychallenge.setValue("sectionid", sectionId);
				dailychallenge.setValue("user", inReq.getUser().getId());
				dailychallenge.setValue("channel", currentchannel.getId());
				dailychallengeSearcher.saveData(dailychallenge);
			}
		}

		if (currentchannel == null)
		{
			return;
		}

		inReq.putPageValue("dailychallenge", dailychallenge);

		inReq.putPageValue("currentchannel", currentchannel);
		inReq.putPageValue("activechannel", currentchannel);

		Collection<Data> previousChallenges = dailychallengeSearcher.query().before("challengedate", today).exact("user", inReq.getUser().getId()).search();
		Collection<String> prevChannels = previousChallenges.stream().map(d -> d.get("channel")).collect(Collectors.toList());

		Collection<Data> history = channelSearcher.query().ids(prevChannels).sort("dateDown").search();
		inReq.putPageValue("channelhistory", history);

		Collection<MultiValued> messages = mediaArchive.query("chatterbox").exact("channel", currentchannel.getId()).orgroup("user", inReq.getUser().getId() + ",agent").sort("dateUp").search();
		inReq.putPageValue("messages", messages);

		Collection<MultiValued> answers = mediaArchive.query("tutoranswer").exact("channel", currentchannel.getId()).exact("user", inReq.getUser().getId()).sort("dateUp").search();
		inReq.putPageValue("answers", answers);

	}

	public void resetTutorial(WebPageRequest inReq)
	{
		Searcher channelSearcher = getMediaArchive(inReq).getSearcher("channel");
		Collection<Data> channels = channelSearcher.query().exact("channeltype", "agenttutorchat").search();
		Collection<String> channelIds = channels.stream().map(d -> d.getId()).collect(Collectors.toList());
		Collection<String> tutorialIds = channels.stream().map(d -> d.get("dataid")).collect(Collectors.toList());

		channelSearcher.deleteAll(channels, null);

		Searcher chatterboxSearcher = getMediaArchive(inReq).getSearcher("chatterbox");
		Collection<Data> chatterboxMessages = chatterboxSearcher.query().orgroup("channel", channelIds).search();
		chatterboxSearcher.deleteAll(chatterboxMessages, null);

		Searcher tutoranswerSearcher = getMediaArchive(inReq).getSearcher("tutoranswer");
		Collection<Data> tutoranswers = tutoranswerSearcher.query().orgroup("channel", channelIds).search();
		tutoranswerSearcher.deleteAll(tutoranswers, null);

		Searcher tutorprogressSearcher = getMediaArchive(inReq).getSearcher("tutorprogress");
		Collection<Data> tutorprogresses = tutorprogressSearcher.query().orgroup("entitytutorial", tutorialIds).search();
		tutorprogressSearcher.deleteAll(tutorprogresses, null);

		Searcher dailySearcher = getMediaArchive(inReq).getSearcher("tutordailychallenge");
		Collection<Data> dailychallenges = dailySearcher.query().orgroup("channel", channelIds).search();
		dailySearcher.deleteAll(dailychallenges, null);

	}

}
