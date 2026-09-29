package org.entermediadb.profile;

import org.entermediadb.asset.modules.BaseMediaModule;
import org.openedit.WebPageRequest;
import org.openedit.hittracker.HitTracker;
import org.openedit.users.UserManager;

public class EmeProfileManager extends BaseMediaModule
{

	public void getAllProfiles(WebPageRequest inReq)
	{
		UserManager userManager = getUserManager(inReq);
		HitTracker allUsers = userManager.getUsers();
		inReq.putPageValue("allusers", allUsers);
	}

}
