package org.entermediadb.profile;

import java.util.List;
import org.entermediadb.ai.BaseAiManager;
import org.entermediadb.asset.MediaArchive;
import org.openedit.WebPageRequest;
import org.openedit.users.User;

public class DirectMessageModule extends BaseAiManager
{
	public DirectMessageManager getDirectMessageManager(WebPageRequest inReq)
	{
		MediaArchive archive = (MediaArchive) inReq.getPageValue("mediaarchive");
		if (archive == null)
		{
			String catalogid = inReq.findPathValue("catalogid");
			if (catalogid != null && !"$catalogid".equals(catalogid))
			{
				archive = (MediaArchive) getModuleManager().getBean(catalogid, "mediaArchive");
			}
		}
		if (archive != null)
		{
			return (DirectMessageManager) archive.getBean("directMessageManager");
		}
		return getDirectMessageManager();
	}

	public DirectMessageManager getDirectMessageManager()
	{
		return (DirectMessageManager) getMediaArchive().getBean("directMessageManager");
	}

	public void initDM(WebPageRequest inReq)
	{
		getDirectMessageManager(inReq).initDM(inReq);
	}

	public void loadChatList(WebPageRequest inReq)
	{
		getDirectMessageManager(inReq).loadChatList(inReq);
	}

	public void loadChat(WebPageRequest inReq)
	{
		getDirectMessageManager(inReq).loadChat(inReq);
	}

}
