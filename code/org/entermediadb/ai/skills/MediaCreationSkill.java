package org.entermediadb.ai.skills;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.Date;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.BaseAiManager;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.assistant.AiCreation;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.asset.Asset;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.OpenEditException;
import org.openedit.repository.ContentItem;
import org.openedit.repository.InputStreamItem;
import org.openedit.users.User;
import org.openedit.util.DateStorageUtil;

public class MediaCreationSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(MediaCreationSkill.class);

	@Override
	public void process(AgentContext inAgentContext)
	{
		ChatMessageContext messageContext = (ChatMessageContext) inAgentContext;

		LlmConnection llmconnection = getMediaArchive().getLlmConnection("thinking");
		LlmResponse response = llmconnection.callStructure(inAgentContext, "creation_image_parse");
		if (response == null)
		{
			log.error("No response from AI for creation_image_parse");
			log.error(response);
			return;
		}

		AiCreation creation = inAgentContext.getAiCreationParams();
		JSONObject content = response.getResponsePayload();
		creation.setCreationFields(content);
		response.setRawMessage("");
		response.setExecAutomationSkill("creation_image_create");
		messageContext.setLastResponse(response);

		// LlmResponse result = createImage(inAgentContext);
		// String assetid = inAgentContext.get("assetid");

		String assetid = "AaAhkPBNO4JkYQUlhlZr";

		Asset asset = getMediaArchive().getAsset(assetid);
		inAgentContext.addContext("asset", asset); // Get the updated asset

		inAgentContext.addContext("refreshing", "true");

		LlmConnection renderllmconnection = getMediaArchive().getLlmConnection("localrender");

		LlmResponse renderresult = renderllmconnection.renderLocalAction(inAgentContext, "creation_image_render");

		log.info("Next function: " + renderresult.getExecAutomationSkill());

		messageContext.setLastResponse(renderresult);
		super.process(inAgentContext);

		return;

	}

	public void processX(AgentContext inAgentContext)
	{
		ChatMessageContext messageContext = (ChatMessageContext) inAgentContext;
		String agentFn = messageContext.getCurrentAutomationStep().getEnabledId();
		if ("creation_image_welcome".equals(agentFn))
		{
			String entityid = inAgentContext.get("entityid");
			String entitymoduleid = inAgentContext.get("entitymoduleid");

			Data entity = getMediaArchive().getCachedData(entitymoduleid, entityid);
			inAgentContext.addContext("entity", entity);

			LlmConnection llmconnection = getMediaArchive().getLlmConnection("localrender");
			LlmResponse response = llmconnection.renderLocalAction(inAgentContext, agentFn);
			// This is for the chat UI to pass it back
			response.setNextAutomationStep("creation_image_parse");
			messageContext.setLastResponse(response);
			return;
		}
		else if ("creation_image_parse".equals(agentFn))
		{
			LlmConnection llmconnection = getMediaArchive().getLlmConnection("thinking");
			LlmResponse response = llmconnection.callStructure(inAgentContext, agentFn);
			if (response == null)
			{
				throw new OpenEditException("No results from AI for function: " + agentFn);
			}
			AiCreation creation = inAgentContext.getAiCreationParams();
			JSONObject content = response.getResponsePayload();
			creation.setCreationFields(content);
			response.setRawMessage("");
			response.setExecAutomationSkill("creation_image_create");
			messageContext.setLastResponse(response);
			return;
		}
		else if ("creation_image_create".equals(agentFn))
		{
			LlmResponse result = createImage(inAgentContext);
			result.setExecAutomationSkill("creation_image_render");
			messageContext.setLastResponse(result);
			return;
		}
		else if ("creation_image_render".equals(agentFn))
		{
			String assetid = inAgentContext.get("assetid");

			Asset asset = getMediaArchive().getAsset(assetid);
			inAgentContext.addContext("asset", asset); // Get the updated asset

			inAgentContext.addContext("refreshing", "true");

			LlmConnection llmconnection = getMediaArchive().getLlmConnection("localrender");

			LlmResponse result = llmconnection.renderLocalAction(inAgentContext, agentFn);

			log.info("Next function: " + result.getExecAutomationSkill());

			messageContext.setLastResponse(result);
			return;

		}
	}

	public LlmResponse createImage(AgentContext inAgentContext)
	{
		MediaArchive archive = getMediaArchive();
		AiCreation aiCreation = inAgentContext.getAiCreationParams();
		LlmConnection llmconnection = archive.getLlmConnection("createimage");

		JSONObject imagefields = (JSONObject) aiCreation.getCreationFields();

		if (imagefields == null)
		{
			return null;
		}

		String prompt = (String) imagefields.get("prompt");

		if (prompt == null)
		{
			return null;
		}

		User chatuser = inAgentContext.getUserProfile().getUser();
		String filename = (String) imagefields.get("image_name");
		LlmResponse results = llmconnection.createImage(prompt);

		for (Iterator iterator = results.getImageBase64s().iterator(); iterator.hasNext();)
		{
			String base64 = (String) iterator.next();

			Asset asset = (Asset) archive.getAssetSearcher().createNewData();

			asset.setValue("importstatus", "created");

			if (filename == null || filename.length() == 0)
			{
				filename = "aiimage_" + System.currentTimeMillis();
			}

			asset.setName(filename + ".png");
			asset.setValue("assettitle", filename);
			asset.setValue("assetaddeddate", new Date());

			String sourcepath = "Channels/" + chatuser.getId() + "/" + DateStorageUtil.getStorageUtil().getTodayForDisplay() + "/" + filename;
			asset.setSourcePath(sourcepath);

			String path = "/WEB-INF/data/" + asset.getCatalogId() + "/originals/" + asset.getSourcePath();
			ContentItem saveTo = archive.getPageManager().getPage(path).getContentItem();

			try
			{
				InputStreamItem revision = new InputStreamItem();

				revision.setAbsolutePath(saveTo.getAbsolutePath());
				revision.setPath(saveTo.getPath());
				revision.setAuthor(chatuser.getId());
				revision.setType(ContentItem.TYPE_ADDED);
				revision.setMessage(saveTo.getMessage());

				revision.setPreviewImage(saveTo.getPreviewImage());

				log.info("Saving image -> " + path + "/" + filename);

				InputStream input = null;

				String code = base64.substring(base64.indexOf(",") + 1, base64.length());
				byte[] tosave = Base64.getDecoder().decode(code);
				input = new ByteArrayInputStream(tosave);

				revision.setInputStream(input);

				archive.getPageManager().getRepository().put(revision);
				asset.setProperty("importstatus", "created");
				archive.saveAsset(asset);
			}
			catch (Exception ex)
			{
				asset.setProperty("importstatus", "error");
				log.error(ex);
				archive.saveAsset(asset);
			}

			// inReq.putPageValue("asset", asset);
			inAgentContext.addContext("asset", asset);
			results.setExecAutomationSkill("creation_image_render");
			inAgentContext.setValue("assetid", asset.getId());
			inAgentContext.setValue("wait", 1000);
			inAgentContext.setLastResponse(results);
		}

		archive.fireSharedMediaEvent("importing/assetscreated");

		return results;
	}
}
