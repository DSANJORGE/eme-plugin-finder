package org.entermediadb.ai.skills;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.util.ArrayList;
import java.util.Collection;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.automation.RunningScenario;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.MultiValued;

public class ChatLoadCollectionAndRolesSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(ChatLoadCollectionAndRolesSkill.class);

	@Override
	public void startupScenario(AgentContext inContext)
	{
		// super.startupScenario(inContext);
		// dont send hi
	}

	@Override
	public void process(AgentContext inAgentContext)
	{
		// TODO Auto-generated method stub
		ChatMessageContext messageContext = (ChatMessageContext) inAgentContext;
		MultiValued agentmessage = messageContext.getAgentMessage();

		MultiValued usermessage = (MultiValued) getMediaArchive().getCachedData("chatterbox", agentmessage.get("replytoid"));

		String query = usermessage.get("message");

		// reset messagereload
		inAgentContext.putContextValue("messagereload", false);

		String entityid = inAgentContext.get("entityid");

		Data channel = messageContext.getChannel();
		String channelsearchtype = channel.get("searchtype");

		Data entity = getMediaArchive().getCachedData(channelsearchtype, entityid);
		if (entity == null)
		{
			log.error("No entity id: " + entityid);
			return;
		}

		String collectionid = null;
		if ("librarycollection".equals(channelsearchtype))
		{
			collectionid = entity.getId();
		}
		else if ("collectiveproject".equals(channelsearchtype))
		{
			collectionid = entity.get("parentcollectionid");
		}
		else if ("goaltask".equals(channelsearchtype))
		{
			collectionid = entity.get("collectionid");
		}

		if (collectionid == null)
		{
			log.error("No collection id found for entity: " + entityid);
			return;
		}

		Collection<MultiValued> teamUsers = getMediaArchive().query("librarycollectionusers").exact("collectionid", collectionid).exact("ontheteam", "true").exists("teamroles").search();
		Collection<Data> roles = new ArrayList<Data>();
		for (MultiValued memeber : teamUsers)
		{
			Collection<String> roleids = memeber.getValues("teamroles");
			for (String roleid : roleids)
			{
				Data role = getMediaArchive().getCachedData("collectiverole", roleid);
				roles.add(role);
			}
		}
		messageContext.putContextValue("roles", roles);

		LlmConnection llmconnection = getMediaArchive().getLlmConnection("thinking");
		LlmResponse response = llmconnection.callToolsFunction(inAgentContext, "emeteamchat_detect");

		log.info(response.getRawResponse());

		messageContext.setLastResponse(response);

		String selectedtool = response.getExecAutomationSkill();
		if (selectedtool == null)
		{
			log.error("No tool selected for query: " + query);
			return;
		}
		String scenario = null;
		if (!selectedtool.contains("."))
		{
			log.error("Selected tool needs the format: scenario.skillenabled. Selected tool:" + selectedtool);
			return;
		}
		scenario = selectedtool.split("\\.")[0];
		String skillenableid = selectedtool.split("\\.")[1];

		log.info("Selected tool: " + selectedtool + " for scenario: " + scenario + " and skill: " + skillenableid);

		JSONObject functionArgs = response.getFunctionArguments();
		inAgentContext.addContext("messagestructured", response.getResponsePayload());
		inAgentContext.addContext("userquery", query);
		inAgentContext.addContext("arguments", functionArgs);

		if (scenario != null)
		{

			RunningScenario running = (RunningScenario) getMediaArchive().getBean("runningscenario", false);
			running.setId(scenario);

			AutomationStep skillEnabled = running.findEnabled(skillenableid);
			if (skillEnabled == null)
			{
				log.error("No skill enabled found for id: " + skillenableid);
				return;
			}
			AgentContext childContext = running.createAgentContext(inAgentContext, skillEnabled);
			childContext.setCurrentScenario(running);

			childContext.putContextValue("cancelstartup" + skillenableid, true);
			childContext.putContextValue("cancelemptyresponse", true);

			childContext.setValue("entityid", collectionid);
			childContext.setValue("entitymoduleid", "librarycollection");

			// Get the docids for the collection and set it in the context
			Collection<String> docids = getAssistantManager().findDocIdsForEntity("librarycollection", collectionid);

			String responserole = (String) response.getResponsePayload().get("role");
			if (responserole == null || responserole.isEmpty())
			{
				log.error("No role selected for query: " + query);
				return;
			}

			String roleid = null;
			for (Data role : roles)
			{
				if (role.getName().equals(responserole))
				{
					roleid = role.getId();
					break;
				}
			}
			String useralias = null;
			Data emeprofile = null;
			for (MultiValued memeber : teamUsers)
			{
				if (memeber.containsValue("teamroles", roleid))
				{
					emeprofile = getAssistantManager().getEmeProfileForUser(memeber.get("followeruser"));
					break;
				}
			}

			if (emeprofile != null)
			{
				Collection<String> profiledocids = getAssistantManager().findDocIdsForEntity("emeprofile", emeprofile.getId());
				docids.addAll(profiledocids);
				useralias = emeprofile.get("owner");
			}
			childContext.putContextValue("docids", docids);
			agentmessage.setValue("useralias", useralias);

			running.runProcess(skillEnabled, childContext);
		}

	}

}
