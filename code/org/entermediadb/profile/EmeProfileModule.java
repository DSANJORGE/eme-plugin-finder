package org.entermediadb.profile;

import java.util.Collection;
import org.entermediadb.ai.BaseAiManager;
import org.entermediadb.asset.MediaArchive;
import org.openedit.WebPageRequest;
import org.openedit.users.User;

public class EmeProfileModule extends BaseAiManager
{
	public EmeProfileManager getEmeProfileManager(WebPageRequest inReq)
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
			return (EmeProfileManager) archive.getBean("emeProfileManager");
		}
		return (EmeProfileManager) getMediaArchive().getBean("emeProfileManager");
	}

	public void getAllProfiles(WebPageRequest inReq)
	{
		getEmeProfileManager(inReq).getAllProfiles(inReq);
	}

}
