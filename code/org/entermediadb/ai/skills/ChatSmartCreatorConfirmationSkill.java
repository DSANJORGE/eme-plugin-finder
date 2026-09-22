package org.entermediadb.ai.skills;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.creator.AiSmartCreatorSteps;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.util.Inflector;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.data.Searcher;

public class ChatSmartCreatorConfirmationSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(ChatSmartCreatorConfirmationSkill.class);

	public void process(AgentContext inContext)
	{
		ChatMessageContext messageContext = (ChatMessageContext) inContext;

		confirmOutline(messageContext, "smartcreator_confirmoutline");

	}

	public LlmResponse confirmOutline(ChatMessageContext messageContext, String agentFn)
	{
		LlmConnection llmconnection = getMediaArchive().getLlmConnection("thinking");

		AiSmartCreatorSteps instructions = messageContext.getAiSmartCreatorSteps();

		if (instructions == null)
		{
			throw new IllegalStateException("Run chat_smartcreator_suggest first");

		}

		// Adjust the outline as needed using regular AI
		messageContext.put("proposedoutline", instructions.getProposedSections());
		Data usermessage = getMediaArchive().getCachedData("chatterbox", messageContext.getAgentMessage().get("replytoid"));
		String prompt = usermessage.get("message");
		messageContext.put("confirmationprompt", prompt);

		LlmResponse res = llmconnection.callStructure(messageContext, "smartcreator_confirmoutline");

		JSONObject updatedSectionsJson = res.getResponsePayload();

		Collection<String> updatedSections = (Collection<String>) updatedSectionsJson.get("updated_outline");
		instructions.setProposedSections(updatedSections);

		boolean wasadjusted = (boolean) updatedSectionsJson.get("changed");

		messageContext.put("changed", wasadjusted);

		if (wasadjusted)
		{
			messageContext.put("proposedoutline", instructions.getProposedSections());

			// Render the updated outline for confirmation
			res.setOperationState("runskill");
			res.setExecAutomationSkill("chat_smartcreator_renderoutline");
			messageContext.setLastResponse(res);

			// AutomationStep skillEnabled = messageContext.getCurrentAgentEnable();
			// messageContext.fireStatusComplete(skillEnabled);
			return res;
		}
		else
		{
			// Create Sections Content
			Data playbackentitymodule = instructions.getTargetModule();
			Data playbackentity = getMediaArchive().getSearcher(playbackentitymodule.getId()).createNewData();
			playbackentity.setValue(messageContext.get("entitymoduleid"), messageContext.get("entityid"));
			String name = instructions.getTitleName();
			name = Inflector.getInstance().capitalize(name);
			playbackentity.setName(name);
			playbackentity.setValue("entity_date", new Date());
			getMediaArchive().saveData(playbackentitymodule.getId(), playbackentity);
			instructions.setTargetEntity(playbackentity);

			initConfirmedSections(instructions);

			// messageContext.put("confirmedoutline", instructions.getConfirmedSections());
			messageContext.put("playbackentity", instructions.getTargetEntity());
			messageContext.put("playbackentitymodule", instructions.getTargetModule());
			super.process(messageContext);

			return res;
		}
	}

	public Collection<Data> initConfirmedSections(AiSmartCreatorSteps inInstructions)
	{

		MediaArchive archive = getMediaArchive();
		Searcher sectionsearcher = archive.getSearcher("componentsection");

		Collection<Data> tosave = new ArrayList<Data>();

		Collection<String> sections = inInstructions.getProposedSections();

		int ordering = 0;
		for (Iterator iterator = sections.iterator(); iterator.hasNext();)
		{
			String outline = (String) iterator.next();

			Data componentSection = sectionsearcher.createNewData();

			componentSection.setName(outline);
			componentSection.setValue("playbackentityid", inInstructions.getTargetEntity().getId());
			componentSection.setValue("playbackentitymoduleid", inInstructions.getTargetModule().getId());
			componentSection.setValue("ordering", ordering);
			componentSection.setValue("creationdate", new Date());
			componentSection.setValue("modificationdate", new Date());

			if (ordering == 0)
			{
				componentSection.setValue("contentrole", "intro");
			}
			else if (!iterator.hasNext())
			{
				componentSection.setValue("contentrole", "conclusion");
			}
			else
			{
				componentSection.setValue("contentrole", "body");
			}

			tosave.add(componentSection);
			ordering++;
		}

		sectionsearcher.saveAllData(tosave, null);

		inInstructions.setConfirmedSections(tosave);

		return tosave;
	}

}
