package org.entermediadb.topic;

import org.entermediadb.ai.BaseAiManager;
import org.openedit.WebPageRequest;

public class TopicModule extends BaseAiManager
{

	public TopicManager getTopicManager()
	{
		return (TopicManager) getMediaArchive().getBean("topicManager");
	}

	public void getUserTopics(WebPageRequest inReq)
	{
		TopicManager topicManager = getTopicManager();
		topicManager.getUserTopics(inReq);
	}

	public void getTopicTutorials(WebPageRequest inReq)
	{
		TopicManager topicManager = getTopicManager();
		topicManager.getTopicTutorials(inReq);
	}

	public void loadTutorial(WebPageRequest inReq)
	{
		TopicManager topicManager = getTopicManager();
		topicManager.loadTutorial(inReq);
	}

	public void loadTutorialHistory(WebPageRequest inReq)
	{
		TopicManager topicManager = getTopicManager();
		topicManager.loadTutorialHistory(inReq);
	}

	public void loadDailyChallenge(WebPageRequest inReq)
	{
		TopicManager topicManager = getTopicManager();
		topicManager.loadDailyChallenge(inReq);
	}

	// FOR TESTING
	public void resetTutorial(WebPageRequest inReq)
	{
		TopicManager topicManager = getTopicManager();
		topicManager.resetTutorial(inReq);
	}

}
