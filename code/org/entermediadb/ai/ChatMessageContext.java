package org.entermediadb.ai;

import java.util.Collection;
import org.entermediadb.ai.llm.BaseAgentContext;
import org.openedit.MultiValued;
import org.json.simple.JSONObject;

public class ChatMessageContext extends BaseAgentContext
{

	public ChatMessageContext(AgentContext inContext) {
		super(inContext);
	}

	public ChatMessageContext() {
		// TODO Auto-generated constructor stub
	}

	public MultiValued getAgentMessage()
	{
		return (MultiValued) getContextValue("agentmessage");
	}

	public void setAgentMessage(MultiValued inMessage)
	{
		putContextValue("agentmessage", inMessage);
	}

	public MultiValued getUserMessage()
	{
		return (MultiValued) getContextValue("usermessage");
	}

	public void setUserMessage(MultiValued inMessage)
	{
		putContextValue("usermessage", inMessage);
	}

	public Collection<MultiValued> getChannelChatHistory()
	{
		return (Collection<MultiValued>) getContextValue("channelchathistory");
	}


	// public void setTutorialId(String inTutorialId)
	// {
	// putContextValue("tutorialid", inTutorialId);
	// }

	// public String getTutorialId()
	// {
	// return (String) getContextValue("tutorialid");
	// }

	// public void setLastSectionId(String inSectionId)
	// {
	// putContextValue("lastsectionid", inSectionId);
	// }

	// public String getLastSectionId()
	// {
	// return (String) getContextValue("lastsectionid");
	// }

	// public void setLastComponentId(String inComponentId)
	// {
	// putContextValue("lastcomponentid", inComponentId);
	// }

	// public String getLastComponentId()
	// {
	// return (String) getContextValue("lastcomponentid");
	// }
}
