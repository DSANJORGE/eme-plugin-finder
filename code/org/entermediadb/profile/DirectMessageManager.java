package org.entermediadb.profile;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.asset.modules.BaseMediaModule;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;
import org.openedit.users.User;

public class DirectMessageManager extends BaseMediaModule
{
	private static final Log log = LogFactory.getLog(DirectMessageManager.class);

	public void initDM(WebPageRequest inReq)
	{
		String fromuser = inReq.getRequestParameter("fromuser");
		if (fromuser == null && inReq.getUser() != null)
		{
			fromuser = inReq.getUser().getId();
		}
		String touser = inReq.getRequestParameter("touser");

		if (fromuser == null || touser == null)
		{
			log.warn("Missing fromuser or touser in request: fromuser=" + fromuser + ", touser=" + touser);
			return;
		}

		MediaArchive archive = getMediaArchive(inReq);

		Searcher colUserSearcher = archive.getSearcher("librarycollectionusers");

		Data colUser = colUserSearcher.query().exact("fromuser", fromuser).exact("followeruser", touser).searchOne();
		if (colUser == null)
		{
			colUser = colUserSearcher.query().exact("fromuser", touser).exact("followeruser", fromuser).searchOne();
		}

		Searcher collectionSearcher = archive.getSearcher("librarycollection");
		Data collection = null;

		if (colUser == null)
		{
			collection = collectionSearcher.createNewData();
			collection.setName(fromuser + " - " + touser);
			collection.setValue("collectiontype", "3");
			collection.setValue("owner", fromuser);
			collection.setValue("creationdate", new Date());
			collectionSearcher.saveData(collection);

			colUser = colUserSearcher.createNewData();
			colUser.setValue("fromuser", fromuser);
			colUser.setValue("followeruser", touser);
			colUser.setValue("collectionid", collection.getId());
			colUser.setValue("ontheteam", "true");
			colUserSearcher.saveData(colUser);
		}
		else
		{
			collection = (Data) collectionSearcher.query().exact("id", colUser.get("collectionid")).searchOne();
		}

		Searcher channelsearcher = archive.getSearcher("channel");

		Data currentchannel = channelsearcher.query().exact("searchtype", "librarycollection").exact("dataid", collection.getId()).searchOne();
		if (currentchannel == null)
		{
			currentchannel = channelsearcher.createNewData();
			currentchannel.setName(fromuser + " - " + touser);
			currentchannel.setValue("searchtype", "librarycollection");
			currentchannel.setValue("dataid", collection.getId());
			currentchannel.setValue("channeltype", "emeteamchat");
			currentchannel.setValue("user", fromuser);

			Calendar now = Calendar.getInstance();
			now.add(Calendar.SECOND, -1);
			currentchannel.setValue("refreshdate", now.getTime());

			channelsearcher.saveData(currentchannel);
		}

		inReq.putPageValue("currentchannel", currentchannel);
	}

	public void loadChatList(WebPageRequest inReq)
	{
		// return a list of users that the current user has a direct message collection with
		String currentuser = inReq.getRequestParameter("fromuser");
		if (currentuser == null && inReq.getUser() != null)
		{
			currentuser = inReq.getUser().getId();
		}

		if (currentuser == null)
		{
			log.warn("Missing user in request for loadChatList");
			inReq.putPageValue("chatlist", Collections.emptyList());
			return;
		}

		MediaArchive archive = getMediaArchive(inReq);

		Collection<Data> memberships = archive.query("librarycollectionusers").or().exact("fromuser", currentuser).exact("followeruser", currentuser).search();

		if (memberships == null || memberships.isEmpty())
		{
			inReq.putPageValue("chatlist", Collections.emptyList());
			return;
		}

		Set<String> collectionids = new LinkedHashSet<>();
		for (Iterator iterator = memberships.iterator(); iterator.hasNext();)
		{
			Data data = (Data) iterator.next();
			String colid = data.get("collectionid");
			if (colid != null && !colid.isEmpty())
			{
				collectionids.add(colid);
			}
		}

		if (collectionids.isEmpty())
		{
			inReq.putPageValue("chatlist", Collections.emptyList());
			return;
		}

		HitTracker dmcollections = archive.query("librarycollection").exact("collectiontype", "3").ids(collectionids).search();

		Collection validDmIds = dmcollections.collectValues("id");
		if (validDmIds == null || validDmIds.isEmpty())
		{
			inReq.putPageValue("chatlist", Collections.emptyList());
			return;
		}

		Collection<Data> teamRecords = archive.query("librarycollectionusers").orgroup("collectionid", validDmIds).search();

		Map<String, String> colToOtherUserId = new HashMap<>();
		Set<String> otherUserIds = new LinkedHashSet<>();
		for (Iterator iterator = teamRecords.iterator(); iterator.hasNext();)
		{
			Data record = (Data) iterator.next();
			String colid = record.get("collectionid");
			String follower = record.get("followeruser");
			String from = record.get("fromuser");

			if (follower != null && !follower.isEmpty() && !follower.equals(currentuser))
			{
				otherUserIds.add(follower);
				if (colid != null && !colToOtherUserId.containsKey(colid))
				{
					colToOtherUserId.put(colid, follower);
				}
			}
			else if (from != null && !from.isEmpty() && !from.equals(currentuser))
			{
				otherUserIds.add(from);
				if (colid != null && !colToOtherUserId.containsKey(colid))
				{
					colToOtherUserId.put(colid, from);
				}
			}
		}

		Map<String, User> userCache = new HashMap<>();
		for (String userId : otherUserIds)
		{
			User user = archive.getUser(userId);
			if (user != null)
			{
				userCache.put(userId, user);
			}
		}

		Collection<Data> channels = archive.query("channel").exact("searchtype", "librarycollection").orgroup("dataid", collectionids).sort("refreshdateDown").search();

		List<Map<String, Object>> chatList = new ArrayList<>();
		Set<String> processedCollections = new HashSet<>();

		if (channels != null)
		{
			for (Iterator iterator = channels.iterator(); iterator.hasNext();)
			{
				Data channel = (Data) iterator.next();
				String channelId = channel.getId();
				String dataid = channel.get("dataid");

				if (dataid != null && processedCollections.contains(dataid))
				{
					continue;
				}

				Data lastMessage = archive.query("chatterbox").exact("channel", channelId).sort("dateDown").searchOne();

				String otherUserId = colToOtherUserId.get(dataid);
				User user = null;
				if (otherUserId != null)
				{
					user = userCache.get(otherUserId);
					if (user == null)
					{
						user = archive.getUser(otherUserId);
					}
				}

				if (user == null)
				{
					continue;
				}

				Map<String, Object> chatMap = new HashMap<>();
				chatMap.put("user", user);
				chatMap.put("lastmessage", lastMessage);
				chatMap.put("channel", channel);
				chatList.add(chatMap);

				if (dataid != null)
				{
					processedCollections.add(dataid);
				}
			}
		}

		inReq.putPageValue("chatlist", chatList);
	}

	public void loadChat(WebPageRequest inReq)
	{
		String channelId = inReq.getRequestParameter("channel");
		if (channelId == null)
		{
			log.warn("Missing channel in request for loadChat");
			return;
		}

		Data channel = getMediaArchive(inReq).query("channel").exact("id", channelId).searchOne();
		if (channel == null)
		{
			log.warn("Channel not found for id: " + channelId);
			return;
		}

		Collection<Data> messages = getMediaArchive(inReq).query("chatterbox").exact("channel", channelId).sort("dateDown").search(inReq);

		inReq.putPageValue("messages", messages);
	}
}
