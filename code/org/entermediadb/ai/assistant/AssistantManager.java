package org.entermediadb.ai.assistant;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseAiManager;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.SkillStatusListener;
import org.entermediadb.ai.automation.AutomationManager;
import org.entermediadb.ai.automation.RunningScenario;
import org.entermediadb.ai.classify.EmbeddingManager;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.BaseAgentContext;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.asset.util.JsonUtil;
import org.entermediadb.find.EntityManager;
import org.openedit.util.HttpSharedConnection;
import org.entermediadb.scripts.ScriptLogger;
import org.entermediadb.util.Inflector;
import org.entermediadb.websocket.chat.ChatServer;
import org.entermediadb.workspace.WorkspaceManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.HttpException;
import org.openedit.MultiValued;
import org.openedit.OpenEditException;
import org.openedit.WebPageRequest;
import org.openedit.cache.CacheManager;
import org.openedit.data.PropertyDetail;
import org.openedit.data.QueryBuilder;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;
import org.openedit.profile.UserProfile;
import org.openedit.users.User;
import org.openedit.util.DateStorageUtil;

public class AssistantManager extends BaseAiManager implements SkillStatusListener
{
	private static final Log log = LogFactory.getLog(AssistantManager.class);

	protected EntityManager getEntityManager()
	{
		return getMediaArchive().getEntityManager();
	}

	public void monitorChannels(ScriptLogger inLog) throws Exception
	{
		MediaArchive archive = getMediaArchive();
		User agent = archive.getUser("agent");
		// TODO: REmove after a while, we checked in one for new installs
		if (agent == null)
		{
			agent = archive.getUserManager().createUser("agent", null);
			agent.setFirstName("AI");
			agent.setLastName("Guide");
			agent.setValue("screenname", "AI Guide");
			archive.getUserManager().saveUser(agent);
			archive.getUserProfileManager().setRoleOnUser(archive.getCatalogId(), agent, "guest");
		}

		Searcher channels = archive.getSearcher("channel");

		// TODO: How Do I know if this is still active?

		Calendar now = DateStorageUtil.getStorageUtil().createCalendar();
		now.add(Calendar.HOUR_OF_DAY, -1);

		// TODO: Only process one "open" channel at a time. What ever the last one they
		// clicked on

		HitTracker allchannels =
			channels.query().orgroup("channeltype", "agentchat,agententitychat,agenttutorchat,emeteamchat,emechat").after("refreshdate", now.getTime()).sort("refreshdateDown").search();

		Searcher chats = archive.getSearcher("chatterbox");
		for (Iterator iterator = allchannels.iterator(); iterator.hasNext();)
		{
			Data data = (Data) iterator.next();
			// log.info("Processing channel: " + data.getId());
			Data channel = (Data) archive.getCachedData("channel", data.getId());
			if (channel.getName() == null) // Make smarter
			{
				Data lastusermessage = chats.query().exact("channel", channel.getId()).not("user", "agent").sort("dateDown").searchOne();

				if (lastusermessage != null)
				{
					String message = lastusermessage.get("message");
					if (message != null)
					{
						if (message.length() > 25)
						{
							message = message.substring(0, 25);
						}
						channel.setName(message.trim());
						archive.saveData("channel", channel);
					}
				}
			}

			Collection mostrecents = chats.query().exact("channel", channel.getId()).orgroup("chatmessagestatus", "received refresh").sort("dateDown").search();

			if (mostrecents.isEmpty())
			{
				continue;
			}

			// Remove from DB
			// TODO: Use a local lock
			Collection tosave = new ArrayList();

			for (Iterator iterator2 = mostrecents.iterator(); iterator2.hasNext();)
			{
				Data lockdata = (Data) iterator2.next();
				lockdata.setValue("chatmessagestatus", "processing");
				tosave.add(lockdata);
			}
			archive.saveData("chatterbox", tosave);

			for (Iterator iterator2 = mostrecents.iterator(); iterator2.hasNext();)
			{
				MultiValued mostrecent = (MultiValued) iterator2.next();
				try
				{
					Runnable runnable = new Runnable() { // Let the user broadcast finish
						public void run()
						{
							respondToChannel(inLog, channel, mostrecent);
						}
					};
					archive.getExecutorManager().execLater(runnable, 0);
				}
				catch (Throwable ex)
				{
					log.error("Could not process message " + mostrecent, ex);
				}
			}

		}
	}

	// public LlmConnection getLlmConnection()
	// {
	// String model = getMediaArchive().getCatalogSettingValue("llmagentmodel");
	// if (model == null)
	// {
	// model = "gpt-5-nano"; // Default fallback
	// }
	// LlmConnection manager = getMediaArchive().getLlmConnection(model);
	// return manager;
	// }
	//

	public ChatMessageContext loadChatContext(String applicationId, Data inChannel)
	{
		MediaArchive archive = getMediaArchive();
		CacheManager cache = archive.getCacheManager();
		ChatMessageContext chatMessageContext = (ChatMessageContext) cache.get("chatMessageContext", inChannel.getId());
		if (chatMessageContext == null)
		{
			chatMessageContext = new ChatMessageContext(loadContext(applicationId, inChannel));

			cache.put("chatMessageContext", inChannel.getId(), chatMessageContext);
		}
		if (chatMessageContext.getChannel() == null)
		{

			chatMessageContext.setChannel(inChannel);
		}
		Collection<MultiValued> messages = archive.query("chatterbox").exact("channel", inChannel.getId()).sort("dateDown").search();
		Collection<MultiValued> filtered = loadChannelChatHistory(messages);
		chatMessageContext.putContextValue("channelchathistory", filtered);
		if (messages.isEmpty())
		{
			return chatMessageContext;
		}

		List<MultiValued> sorted = new ArrayList<>();
		sorted.addAll(messages);
		Collections.reverse(sorted);

		for (Iterator<MultiValued> iterator = sorted.iterator(); iterator.hasNext();)
		{
			MultiValued message = iterator.next();
			String agentcontextvalues = message.get("agentcontextvalues");
			if (agentcontextvalues != null)
			{
				chatMessageContext.loadContextFromJson(agentcontextvalues);
			}
		}

		Object currentscenario = chatMessageContext.getContextValue("currentscenario");
		if (currentscenario instanceof String)
		{
			RunningScenario running = (RunningScenario) getMediaArchive().getBean("runningscenario", false);
			running.setId((String) currentscenario);
			chatMessageContext.setCurrentScenario(running);
		}

		return chatMessageContext;
	}

	public AgentContext loadContext(String applicationId, Data inChannel)
	{
		MediaArchive archive = getMediaArchive();
		AgentContext agentContext = (AgentContext) archive.getCacheManager().get("agentContext", inChannel.getId());
		if (agentContext == null)
		{
			// TODO: Replace agentcontext with chatterbox message data (load messages and their add their data
			// to the context)
			Searcher searcher = archive.getSearcher("agentcontext");
			agentContext = (AgentContext) searcher.query().exact("channel", inChannel.getId()).searchOne();
			// hitory?
			if (agentContext == null)
			{
				agentContext = new BaseAgentContext();
				Data channel = getMediaArchive().getCachedData("channel", inChannel.getId());
				if (channel == null)
				{
					log.error("Should not have to create new channel");
					channel = getMediaArchive().getSearcher("channel").createNewData();
					channel.setId(inChannel.getId());
					channel.setValue("date", new Date());
					channel.setValue("refreshdate", new Date());
					// String siteid = PathUtilities.extractDirectoryPath(getMediaArchive().getCatalogId());
					getMediaArchive().saveData("channel", channel);
				}

				agentContext.setValue("channel", inChannel.getId());
				String entitymoduleid = channel.get("searchtype");
				if (channel.get("dataid") != null)
				{
					agentContext.setValue("entityid", channel.get("dataid"));
					agentContext.setValue("entitymoduleid", entitymoduleid);

				}
			}

			String communitytagid = getMediaArchive().getPageManager().getPage("/" + applicationId + "/").getProperty("communitytag");
			Data communitytag = getMediaArchive().getCachedData("communitytag", communitytagid);
			if (communitytag != null)
			{
				agentContext.putContextValue("siteroot", communitytag.get("externaldomain"));
			}

			agentContext.setChannel(inChannel);

			Data entity = archive.getCachedData((String) agentContext.getValue("entitymoduleid"), agentContext.get("entityid"));
			Data entitymodule = archive.getCachedData("module", agentContext.get("entitymoduleid"));
			agentContext.addContext("entity", entity);
			agentContext.addContext("entitymodule", entitymodule);

			// MultiValued currentscenario = (MultiValued) archive.getCachedData("automationscenario",
			// agentContext.get("currentscenario"));
			// agentContext.setCurrentScenario(currentscenario);

			String currentScenario = agentContext.get("currentscenario");
			if (currentScenario != null)
			{
				RunningScenario running = (RunningScenario) getMediaArchive().getBean("runningscenario", false);;
				running.setId(currentScenario);
				agentContext.setCurrentScenario(running);
			}

		}
		return agentContext;
	}

	public void respondToChannel(ScriptLogger inLog, Data inChannel, MultiValued usermessage)
	{
		MediaArchive archive = getMediaArchive();

		String applicationid = getMediaArchive().getCatalogSettingValue("mediadbappid");
		ChatMessageContext chatMessageContext = loadChatContext(applicationid, inChannel);

		ChatServer server = (ChatServer) archive.getBean("chatServer");

		String channeltype = inChannel.get("channeltype");
		if (channeltype == null)
		{
			channeltype = "agentchat";
		}

		String id = inChannel.get("user");
		UserProfile profile = archive.getUserProfile(id);
		chatMessageContext.putContextValue("chatprofile", profile);
		chatMessageContext.setUserProfile(profile);

		chatMessageContext.putContextValue("channel", inChannel);

		// String oldstatus = usermessage.get("chatmessagestatus");

		// Update original message processing status
		usermessage.setValue("chatmessagestatus", "completed");
		getMediaArchive().saveData("chatterbox", usermessage); // Update the user message again to finish it

		chatMessageContext.putContextValue("message", usermessage);

		chatMessageContext.putContextValue("assistant", this);

		// Add new agentmessage
		MultiValued agentmessage = newAgentMessage(usermessage, chatMessageContext);
		chatMessageContext.setAgentMessage(agentmessage);
		// ChatServer server = (ChatServer) getMediaArchive().getBean("chatServer");
		// Determine what will need to be processed
		try
		{

			String playbackentitymoduleid = inChannel.get("playbackentitymoduleid");
			if (playbackentitymoduleid != null)
			{
				Integer playbacksection = (Integer) inChannel.getValue("playbacksection");
				if (playbacksection != null)
				{
					chatMessageContext.putContextValue("playbacksection", playbacksection);
				}
				else
				{
					chatMessageContext.putContextValue("playbacksection", 0);
				}
			}
			else
			{
				if (chatMessageContext.getCurrentScenario() == null)
				{
					log.error("This should never happen");
					return;
				}
			}
			String functionName = usermessage.get("functionname");
			if (functionName == null)
			{
				log.error("Should we have a function");
			}
			if (functionName != null)
			{
				execCurrentFunctionFromChat(chatMessageContext, usermessage, functionName);
			}
		}
		catch (Exception ex)
		{
			agentmessage.setValue("message", ex.toString());
			archive.saveData("chatterbox", agentmessage);
			server.broadcastMessage(archive.getCatalogId(), agentmessage);
		}
	}

	public MultiValued newAgentMessage(MultiValued usermessage, ChatMessageContext chatMessageContext)
	{
		MultiValued agentmessage = (MultiValued) getMediaArchive().getSearcher("chatterbox").createNewData();
		agentmessage.setValue("user", "agent");
		agentmessage.setValue("replytoid", usermessage.getId());
		agentmessage.setValue("channel", chatMessageContext.getChannel().getId());
		agentmessage.setValue("date", new Date());
		agentmessage.setValue("chatmessagestatus", "processing");
		return agentmessage;
	}

	// MultiValued usermessage, MultiValued agentmessage, chatMessageContext chatMessageContext
	public void execCurrentFunctionFromChat(ChatMessageContext chatMessageContext, MultiValued usermessage, String functionName)
	{

		MultiValued agentmessage = chatMessageContext.getAgentMessage();

		MediaArchive archive = getMediaArchive();

		Data channel = archive.getCachedData("channel", agentmessage.get("channel"));
		chatMessageContext.putContextValue("channel", channel);

		// Define alias on emeprofile chats
		if ("emeprofile".equals(channel.get("searchtype")))
		{
			Data emeprofile = getMediaArchive().getCachedData("emeprofile", channel.get("dataid"));
			if (emeprofile != null)
			{
				String useralias = emeprofile.get("owner");
				agentmessage.setValue("useralias", useralias);
			}
		}

		chatMessageContext.putContextValue("usermessage", usermessage);
		chatMessageContext.putContextValue("agentmessage", agentmessage);

		// chatMessageContext.putContextValue("aisearchparams", chatMessageContext.getAiSearchParams() );
		// // ??

		String apphome = "/" + getMediaArchive().getCatalogSettingValue("mediadbappid");
		chatMessageContext.putContextValue("apphome", apphome);

		// LlmResponse response = null;
		// String messagePrefix = chatMessageContext.getMessagePrefix();

		// ChatMessageContext messageContext = new ChatMessageContext(chatMessageContext);// Needed?
		chatMessageContext.setAgentMessage(agentmessage);
		chatMessageContext.setUserMessage(usermessage);
		chatMessageContext.addStatusListener(this);
		try
		{
			// get the scenerio and run that. Each scenerio will have one or more skills
			RunningScenario scenerio = chatMessageContext.getCurrentScenario();

			chatMessageContext.setLastResponse(null);
			scenerio.runProcess(functionName, chatMessageContext);

			// getAutomationManager().runScenario(scenerio.getId(), chatMessageContext);

		}
		catch (HttpException e)
		{
			log.error("Error from " + chatMessageContext.getCurrentScenario(), e);
			handleError(chatMessageContext, e.getMessage(), e.getErrorcode());
		}
		catch (Exception e)
		{
			log.error("Error from " + chatMessageContext.getCurrentScenario(), e);
			handleError(chatMessageContext, e.getMessage());
		}
		// agentmessage.setValue("functionresponse", e.toString());
		// agentmessage.setValue("chatmessagestatus", "failed");
		// archive.saveData("chatterbox", agentmessage);
		finally
		{
			getMediaArchive().saveData("chatMessageContext", chatMessageContext);
		}
	}

	protected int channelMessageCount(String inChannelId)
	{
		HitTracker messages = getMediaArchive().query("chatterbox").exact("channel", inChannelId).sort("dateUp").search();
		int total = (int) messages.size();
		return total;
	}

	protected Collection<MultiValued> loadChannelChatHistory(Collection<MultiValued> messages)
	{
		// HitTracker messages = getMediaArchive().query("chatterbox").exact("channel",
		// inChannel).sort("dateUp").search();

		Collection<MultiValued> recent = new ArrayList<MultiValued>();

		for (Iterator<MultiValued> iterator = messages.iterator(); iterator.hasNext();)
		{
			MultiValued message = iterator.next();
			if ("system".equals(message.get("messagetype")))
			{
				continue;
			}
			if ("agent".equals(message.get("user")))
			{
				String plainmessage = message.get("messageplain");
				if (plainmessage == null || plainmessage.isEmpty())
				{
					continue;
				}
			}
			recent.add(message);
		}

		return recent;
	}

	/*
	 * public HitTracker getFunctions() { HitTracker hits =
	 * getMediaArchive().query("aifunctions").exact("pipeline", "assistant").exact("enabled",
	 * true).sort("ordering").cachedSearch(); return hits; }
	 */

	// TODO Not used?
	public void addMcpVars(WebPageRequest inReq, AiSearch searchArgs)
	{
		// Collection<String> keywords = searchArgs.getKeywords();
		// inReq.putPageValue("keywordsstring",
		// getResultsManager().joinWithAnd(keywords));
		//
		// Collection<Data> modules = searchArgs.getSelectedModules();
		//
		//
		// Collection<String> moduleNames = new ArrayList<String>();
		//
		// for (Iterator iterator = modules.iterator(); iterator.hasNext();)
		// {
		// Data module = (Data) iterator.next();
		// if(!moduleNames.contains(module.getName()))
		// {
		// moduleNames.add(module.getName());
		// }
		// }
		//
		// inReq.putPageValue("modulenamestext",
		// getResultsManager().joinWithAnd(moduleNames));

	}

	/*
	 * public String generateReport(JSONObject arguments) throws Exception { Collection<String> keywords
	 * = getResultsManager().parseKeywords(arguments.get("keywords"));
	 * 
	 * MediaArchive archive = getMediaArchive();
	 * 
	 * HitTracker pdfs = archive.query("asset").freeform("description", String.join(" ",
	 * keywords)).search();
	 * 
	 * Collection<String> pdfTexts = new ArrayList<String>();
	 * 
	 * for (Iterator iterator = pdfs.iterator(); iterator.hasNext();) { Data pdf = (Data)
	 * iterator.next(); String text = (String) pdf.getValue("fulltext"); if(text != null &&
	 * text.length() > 0) { pdfTexts.add(text); } log.info(text); }
	 * 
	 * String fullText = String.join("\n\n", pdfTexts);
	 * 
	 * if(fullText.replaceAll("\\s|\\n", "").length() == 0) { return null; }
	 * 
	 * chatMessageContext chatMessageContext = new chatMessageContext();
	 * chatMessageContext.putContextValue("fulltext", fullText);
	 * 
	 * String model = archive.getCatalogSettingValue("llmmcpmodel"); if(model == null) { model =
	 * "gpt-5-nano"; } chatMessageContext.putContextValue("model", model);
	 * 
	 * LlmConnection llmconnection = (LlmConnection) archive.getBean("openaiConnection");
	 * 
	 * String chattemplate = "/" + archive.getMediaDbId() +
	 * "/ai/openai/mcp/prompts/generate_report.json"; LlmResponse response =
	 * llmconnection.runPageAsInput(chatMessageContext, chattemplate);
	 * 
	 * String report = response.getMessage();
	 * 
	 * return report; }
	 */

	// TODO Not used?
	public Collection<PropertyDetail> getCommonFields(String inSearchtype)
	{
		Collection<PropertyDetail> fields = new ArrayList();
		PropertyDetail pd = getMediaArchive().getSearcher(inSearchtype).getPropertyDetails().createDetail("title");
		// Collection<String> fieldids =
		// Arrays.from("title","description","keywords","caption","date");
		fields.add(pd);

		// fields.add("description");
		// fields.add("keywords");
		// fields.add("caption");
		// fields.add("date");
		return fields;
	}

	// public void hybridSearch(WebPageRequest inReq) throws Exception {
	//
	// AiSearch aiSearchArgs = processSematicSearchArgs(arguments, userprofile);
	//
	// getResultsManager().searchByKeywords(inReq, aiSearchArgs);
	//
	// int totalhits = (int) inReq.getPageValue("totalhits");
	// if(totalhits < 5)
	// {
	// inReq.putPageValue("query", String.join(" ", aiSearchArgs.getKeywords()));
	// semanticSearch(inReq);
	// }
	//
	// }

	public Collection<GuideStatus> getGuideStatus(Data inEntityModule, Data inEntity)
	{
		Collection<GuideStatus> statuses = new ArrayList<GuideStatus>();

		Collection<Data> found = findEmbeddingEnabledEntityIds(inEntityModule.getId(), inEntity.getId(), null);

		if (found.isEmpty())
		{
			return statuses;
		}

		Map<String, GuideStatus> statusMap = new HashMap();

		Map<String, Collection<MultiValued>> missingEmbeddings = new HashMap();

		for (Iterator iterator = found.iterator(); iterator.hasNext();)
		{
			MultiValued data = (MultiValued) iterator.next();
			String searchtype = data.get("entitysourcetype");

			GuideStatus status = statusMap.get(searchtype);
			if (status == null)
			{
				status = new GuideStatus();
				status.setSearchType(searchtype);

				statusMap.put(searchtype, status);

				statuses.add(status);
			}

			String embeddingstatus = data.get("entityembeddingstatus");
			if (embeddingstatus == null)
			{
				Collection<MultiValued> missing = missingEmbeddings.get(searchtype);
				if (missing == null)
				{
					missing = new ArrayList();
					missingEmbeddings.put(searchtype, missing);
				}
				missing.add(data);
				status.setCountPending(status.getCountPending() + 1);
			}
			else
			{
				switch (embeddingstatus)
				{
					case "embedded":
						status.setCountEmbedded(status.getCountEmbedded() + 1);
						break;
					case "pending":
						status.setCountPending(status.getCountPending() + 1);
						break;
					case "failed":
						status.setCountFailed(status.getCountFailed() + 1);
						break;
					default:
						throw new OpenEditException("Unknown embedding status: " + embeddingstatus);

				}
			}

			status.setCountTotal(status.getCountTotal() + 1);

		}

		getEmbeddingManager().queueMissingEmbeddings(missingEmbeddings);

		return statuses;
	}

	public Collection<String> findDocIdsForEntity(String parentmoduleid, String inEntityId)
	{
		return findDocIdsForEntity(parentmoduleid, inEntityId, "embedded");
	}

	public Collection<String> findDocIdsForEntity(String parentmoduleid, String inEntityId, String embeddingType)
	{
		Collection<Data> docs = findEmbeddingEnabledEntityIds(parentmoduleid, inEntityId, embeddingType);

		JSONArray docids = new JSONArray();

		for (Iterator iterator = docs.iterator(); iterator.hasNext();)
		{
			MultiValued doc = (MultiValued) iterator.next();
			String type = doc.get("entitysourcetype");
			String docid = type + "_" + doc.getId();
			docids.add(docid);
		}

		return docids;
	}

	public Collection<Data> findEmbeddingEnabledEntityIds(String parentmoduleid, String inEntityId, String embeddingType)
	{
		MediaArchive archive = getMediaArchive();

		// Data inEntityModule = archive.getCachedData("module", parentmoduleid);

		Data inEntity = archive.getCachedData(parentmoduleid, inEntityId);
		Collection<Data> docs = new ArrayList();

		// Always Check itself first
		PropertyDetail detail = getMediaArchive().getSearcher(parentmoduleid).getDetail("entityembeddingstatus");
		if (detail != null)
		{
			String mystatus = inEntity.get("entityembeddingstatus");

			if (embeddingType == null || embeddingType.equals(mystatus))
			{
				docs.add(inEntity);
			}
		}

		Collection detailsviews = getMediaArchive().query("view").exact("moduleid", parentmoduleid).exact("systemdefined", false).cachedSearch();

		for (Iterator iterator = detailsviews.iterator(); iterator.hasNext();)
		{
			Data view = (Data) iterator.next();

			String listid = view.get("rendertable");
			if (listid != null)
			{
				String renderexternalid = view.get("renderexternalid");
				String renderinternalid = view.get("renderinternalid");
				if (renderinternalid == null)
				{
					renderinternalid = "id";
				}
				String parentid = inEntity.get(renderinternalid);

				if (renderexternalid == null || parentid == null)
				{
					continue;
				}

				QueryBuilder query = archive.query(listid).exact(renderexternalid, parentid);
				if (embeddingType == null)
				{
					query.exists("entityembeddingstatus");
				}
				else
				{
					query.exact("entityembeddingstatus", embeddingType);
				}

				HitTracker hits = query.search();

				for (Iterator iterator2 = hits.iterator(); iterator2.hasNext();)
				{
					MultiValued doc = (MultiValued) iterator2.next();

					docs.add(doc);
				}
			}
		}

		return docs;
	}

	public AutomationManager getAutomationManager()
	{
		AutomationManager manager = (AutomationManager) getMediaArchive().getBean("automationManager");
		return manager;
	}

	public EmbeddingManager getEmbeddingManager()
	{
		EmbeddingManager manager = (EmbeddingManager) getMediaArchive().getBean("embeddingManager");
		return manager;
	}

	public void sendSystemMessage(ChatMessageContext inContext, String inUser, String message, String functionname)
	{
		MediaArchive archive = getMediaArchive();

		// currentchannel set next function
		// Save a message
		Data chat = archive.getSearcher("chatterbox").createNewData();
		chat.setValue("messagetype", "system");
		chat.setValue("user", inUser);
		chat.setValue("date", new Date());
		chat.setValue("functionname", functionname);
		chat.setValue("chatmessagestatus", "received");
		chat.setValue("channel", inContext.getChannel().getId());
		chat.setValue("message", message);

		archive.saveData("chatterbox", chat);
		// inReq.putPageValue("chat", chat);

		// Fire monitor
		archive.fireSharedMediaEvent("llm/monitorchats");

	}

	public void monitorAiServers(ScriptLogger inLog)
	{
		Collection<Data> currentservers = getMediaArchive().query("aiserver").exact("monitorspeed", true).search();

		Map<String, Integer> speeds = new HashMap();
		ArrayList tosave = new ArrayList();

		for (Data server : currentservers)
		{
			String serverroot = server.get("serverroot");
			String address = serverroot + "/health";

			Integer speed = speeds.get(serverroot);
			if (speed == null || speed == 0)
			{
				HttpSharedConnection connection = new HttpSharedConnection();
				String key = server.get("serverapikey");
				if (key != null)
				{
					connection.addSharedHeader("Authorization", "Bearer " + server);
				}
				long start = System.currentTimeMillis();
				try
				{
					JSONObject got = connection.getJson(address);
					if (got != null)
					{
						String ok = (String) got.get("status");
						if ("ok".equals(ok))
						{
							long end = System.currentTimeMillis();
							Integer diff = Math.round(end - start);
							inLog.info(address + " ok run in " + diff + " milliseconds");
							speeds.put(serverroot, diff);
						}
					}
				}
				catch (Exception ex)
				{
					inLog.info(address + " had error " + ex);
					speeds.put(serverroot, Integer.MAX_VALUE); // Push back
					// Ignore
				}
			}
		}
		if (!speeds.isEmpty())
		{
			inLog.info("Saving " + speeds);
			for (Data server : currentservers)
			{
				String serverroot = server.get("serverroot");
				Integer speed = speeds.get(serverroot);
				server.setValue("ordering", speed);
				tosave.add(server);
			}
			getMediaArchive().getSearcher("aiserver").saveAllData(tosave, null);
			getMediaArchive().getCacheManager().clear("llmconnection");
		}
	}

	public void resetAiServers(ScriptLogger inLog)
	{
		HashMap<String, String> keys = new HashMap();
		Collection<Data> currentservers = getMediaArchive().query("aiserver").all().search();
		for (Iterator iterator = currentservers.iterator(); iterator.hasNext();)
		{
			Data data = (Data) iterator.next();
			keys.put(data.getId(), data.get("serverapikey"));
		}

		Searcher aiserverSearcher = getMediaArchive().getSearcher("aiserver");
		aiserverSearcher.resetMappings();

		List tosave = new ArrayList();
		currentservers = getMediaArchive().query("aiserver").all().search();
		for (Iterator iterator = currentservers.iterator(); iterator.hasNext();)
		{
			Data data = (Data) iterator.next();
			String key = keys.get(data.getId());
			if (key != null && !key.equals(data.get("serverapikey")))
			{
				data.setValue("serverapikey", key);
				tosave.add(data);

			}
		}
		aiserverSearcher.saveAllData(tosave, null);
	}

	public void addMissingFunctions(ScriptLogger inLog)
	{
		Collection<Data> modules = getMediaArchive().getList("module");
		List tosave = new ArrayList();

		// reset ai servers
		resetAiServers(inLog);

		// reset aifunctions table
		Searcher aifunctionSearcher = getMediaArchive().getSearcher("aifunction");
		aifunctionSearcher.resetMappings();

		List usetext = new ArrayList();
		int existing = 0;
		int created = 0;
		for (Iterator iterator = modules.iterator(); iterator.hasNext();)
		{
			MultiValued module = (MultiValued) iterator.next();
			String method = module.get("aicreationmethod");

			if (method == null)
			{
				continue;
			}

			String id = "";
			String messagehandler = "";

			if (method.equals("fieldsonly"))
			{
				id = "fieldsonly_welcome_" + module.getId();
				messagehandler = "entityCreationSkill";
			}
			else if (method.equals("smartcreator"))
			{
				id = "smartcreator_welcome_" + module.getId();
				messagehandler = "smartCreatorSkill";
			}

			Data exists = getMediaArchive().getData("aifunction", id);
			if (exists != null)
			{
				existing++;
				inLog.info(id + " AI function exists" + module.getName());
				continue;
			}

			// Add all these to ollamat
			Data welcome_aifunction = aifunctionSearcher.createNewData();
			welcome_aifunction.setId(id);
			welcome_aifunction.setValue("messagehandler", messagehandler);
			welcome_aifunction.setValue("toplevel", true);

			String singular = module.getName();
			singular = Inflector.getInstance().singularize(singular);
			welcome_aifunction.setName("Create " + singular);
			welcome_aifunction.setValue("icon", module.get("moduleicon"));
			tosave.add(welcome_aifunction);

			created++;
			inLog.info(id + " AI function created for " + module.getName());
		}
		getMediaArchive().saveData("aifunction", tosave);

		inLog.info("Functions created: " + created + " Existing: " + existing);

		Set tosaveservers = new HashSet();
		Collection servers = getMediaArchive().getList("aiserver");
		for (Iterator iterator = servers.iterator(); iterator.hasNext();)
		{
			MultiValued server = (MultiValued) iterator.next();
			if (server.getId().startsWith("llamat"))
			{
				for (Iterator iterator2 = usetext.iterator(); iterator2.hasNext();)
				{
					Data function = (Data) iterator2.next();
					if (!server.hasValue(function.getId()))
					{
						server.addValue("aifunctions", function.getId());
						tosaveservers.add(server);
					}
				}
			}
		}
		getMediaArchive().saveData("aiserver", tosaveservers);
		inLog.info("Updated servers " + tosaveservers.size());

		// Add AI functions to mediadb
		WorkspaceManager workspaceManager = (WorkspaceManager) getMediaArchive().getBean("workspaceManager");
		workspaceManager.createMediaDbAiFunctionEndPoints(getMediaArchive().getCatalogId());

		// Clear Cache
		getMediaArchive().clearAll();
	}

	public Collection<String> getModulesAsEnum()
	{
		Collection<String> nameenums = new HashSet<String>();
		for (Data module : loadSchema().getModules())
		{
			String name = module.getName();
			nameenums.add(name);

		}
		// add asset types
		Collections.addAll(nameenums, "files", "images", "videos", "documents", "audio");

		return nameenums;
	}

	public void addSmartCreatorTypes(ScriptLogger inLog)
	{
		Collection<Data> modules = getMediaArchive().query("module").exact("aicreationmethod", "smartcreator").search();
		List tosave = new ArrayList();

		for (Iterator iterator = modules.iterator(); iterator.hasNext();)
		{
			Data module = (Data) iterator.next();
			String type = module.get("searchtype");
			if (type == null)
			{
				log.info("Module " + module.getId() + " does not have searchtype, skipping");
				continue;
			}

			Data scenario = getMediaArchive().getCachedData("automationscenario", "smartcreator_" + type);
			if (scenario == null)
			{
				scenario = getMediaArchive().getSearcher("automationscenario").createNewData();
				scenario.setId("smartcreator_" + type);

				// save isrunning="false" isvisible="true" ordering="50" scenarioicon="broadcast" enabled="true"
				// connectedtop="chatlabel"
				scenario.setValue("enabled", true);
				scenario.setValue("isvisible", true);
				scenario.setValue("scenarioicon", "broadcast");
				scenario.setValue("isrunning", false);
				scenario.setValue("ordering", 50);
				scenario.setValue("connectedtop", "chatlabel");

				scenario.setName("Smart Creator for " + module.getName());
				tosave.add(scenario);
				log.info("Created scenario for smart creator: " + scenario.getId());
			}
		}
		getMediaArchive().saveData("automationscenario", tosave);
	}

	public void handleStatusStarting(AgentContext inContext, AutomationStep inAutomationStep)
	{
		if (!(inContext instanceof ChatMessageContext))
		{
			return;
		}

		ChatMessageContext chatMessageContext = (ChatMessageContext) inContext;

		boolean skiploader = Boolean.parseBoolean((String) chatMessageContext.getContextValue("skiploader"));

		if (skiploader)
		{
			// chatMessageContext.putContextValue("skiploader", Boolean.FALSE);
			return;
		}

		MultiValued function = inAutomationStep.getAutomationStepData();

		JsonUtil jsonUtil = (JsonUtil) getMediaArchive().getBean("jsonUtil");

		String processingmessage = null;
		if (function != null)
		{
			processingmessage = function.get("processingmessage");
		}
		if (processingmessage == null)
		{
			processingmessage = "Analyzing";
		}

		String processingtype = (String) inContext.getContextValue("processingtype");
		if (processingtype != null)
		{
			processingmessage += " " + processingtype;
		}
		String loader = jsonUtil.escape("<i class='fas fa-spinner fa-spin'></i> ");
		processingmessage = loader + processingmessage + "...";
		processingmessage = "<span class='processing-message'>" + processingmessage + "</span>";

		MultiValued agentmessage = chatMessageContext.getAgentMessage();

		String message = inContext.getMessagePrefix() + processingmessage;
		agentmessage.setValue("message", message); // setting status
		agentmessage.setValue("functionname", function.getId());
		getMediaArchive().saveData("chatterbox", agentmessage);
		ChatServer server = (ChatServer) getMediaArchive().getBean("chatServer");
		server.broadcastMessage(getMediaArchive().getCatalogId(), agentmessage);

	}

	public void handleStatusComplete(AgentContext inContext, AutomationStep inAutomationStep)
	{

		MultiValued agentmessage = (MultiValued) inContext.getContextValue("agentmessage");
		if (agentmessage == null)
		{
			return;
		}
		LlmResponse response = inContext.getLastResponse();

		try
		{
			String updatedMessage = null;
			String messagePrefix = inContext.getMessagePrefix();

			if (response != null && response.getMessage() != null)
			{
				if (messagePrefix != null)
				{
					updatedMessage = messagePrefix + response.getMessage();
				}
				else
				{
					updatedMessage = response.getMessage();
				}
			}
			if (updatedMessage != null)
			{
				agentmessage.setValue("message", updatedMessage); // Final message
			}

			String messageplain = agentmessage.get("messageplain");
			if (response != null)
			{
				String newmessageplain = response.getMessagePlain();

				if (newmessageplain != null)
				{
					if (messageplain == null)
					{
						messageplain = newmessageplain;
					}
					else
					{
						messageplain += " \n " + newmessageplain;
					}
					agentmessage.setValue("messageplain", messageplain);
				}
			}
			String nextFunctionName = null;
			if (response == null)
			{
				log.error("Skipping null responses");
			}
			else
			{
				nextFunctionName = response.getNextSkillEnabled();
			}

			if (nextFunctionName == null)
			{
				AutomationStep nextEnabled = inAutomationStep.getNextAutomationStep();
				if (nextEnabled != null)
				{
					nextFunctionName = nextEnabled.getEnabledId();
				}
			}

			agentmessage.setValue("functionname", inAutomationStep.getEnabledId());
			agentmessage.setValue("nextfunctionname", nextFunctionName);
			agentmessage.setValue("chatmessagestatus", "completed");

			agentmessage.setValue("agentcontextvalues", inContext.toJSONString());

			getMediaArchive().saveData("chatterbox", agentmessage);

			Map<String, String> functionMessageUpdate = new HashMap<>();
			functionMessageUpdate.put("messagetype", "airesponse");
			functionMessageUpdate.put("catalogid", getMediaArchive().getCatalogId());
			functionMessageUpdate.put("user", "agent");
			functionMessageUpdate.put("channel", agentmessage.get("channel"));
			functionMessageUpdate.put("messageid", agentmessage.getId());
			if (messageplain == null)
			{
				messageplain = "New message";
			}
			functionMessageUpdate.put("message", updatedMessage);
			functionMessageUpdate.put("agentcontextvalues", agentmessage.get("agentcontextvalues"));
			functionMessageUpdate.put("messageplain", messageplain);
			functionMessageUpdate.put("nextfunctionname", nextFunctionName);
			functionMessageUpdate.put("functionname", inAutomationStep.getEnabledId());
			functionMessageUpdate.put("date", DateStorageUtil.getStorageUtil().getJsonFormat().format(new Date()));
			Boolean messagereload = (Boolean) inContext.getContextValue("messagereload");

			if (messagereload != null && messagereload.booleanValue())
			{
				functionMessageUpdate.put("command", "messagereload");
			}

			ChatServer server = (ChatServer) getMediaArchive().getBean("chatServer");

			JSONObject jsonMessage = new JSONObject(functionMessageUpdate);

			log.info("Broadcasting: " + jsonMessage.toJSONString());

			server.broadcastMessage(jsonMessage);

		}
		catch (Exception ex)
		{
			log.error("Error in fireStatusComplete", ex);
		}

		Long waittime = 200l;

		RunningScenario currentscenario = inContext.getCurrentScenario();
		if (currentscenario != null)
		{
			Long wait = inContext.getWaitTime();
			if (wait != null && wait instanceof Long)
			{
				inContext.setWaitTime(null);
				waittime = wait;
				log.info("Previous function requested to wait " + waittime + " milliseconds");
				try
				{
					Thread.sleep(waittime);
				}
				catch (InterruptedException ex)
				{
					log.warn("Sleep interrupted", ex);
					Thread.currentThread().interrupt();
				}
			}

			// chatMessageContext.setAgentMessage(agentmessage);
			// chatMessageContext.setUserMessage(usermessage);

			// String runFunctionName = response.getRunSkillEnabled();
			// if (runFunctionName != null)
			// {
			// MultiValued nextFunction = (MultiValued) archive.getCachedData("aifunction", runFunctionName);
			// chatMessageContext.setCurrentFunction(nextFunction);
			// execCurrentFunctionFromChat(chatMessageContext, usermessage, runFunctionName);
			// }
			// // Save the current state
		}
	}

	public Data getEmeProfileForUser(String userid)
	{
		Data emeprofile = getMediaArchive().query("emeprofile").exact("owner", userid).searchOne();
		if (emeprofile != null)
		{
			return emeprofile;
		}
		return null;
	}

}
