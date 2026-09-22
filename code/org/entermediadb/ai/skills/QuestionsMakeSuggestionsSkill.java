package org.entermediadb.ai.skills;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.ChatMessageContext;
import org.entermediadb.ai.BaseAiManager;
import org.entermediadb.ai.Schema;
import org.entermediadb.ai.classify.EmbeddingManager;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.assistant.AssistantManager;
import org.entermediadb.ai.assistant.GuideStatus;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.asset.Asset;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.OpenEditException;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;

public class QuestionsMakeSuggestionsSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(QuestionsMakeSuggestionsSkill.class);

	@Override
	public void process(AgentContext inAgentContext)
	{
		ChatMessageContext messageContext = (ChatMessageContext) inAgentContext;
		///
		MultiValued inAgentMessage = messageContext.getAgentMessage();
		String agentFn = messageContext.getCurrentAutomationStep().getEnabledId();

		MultiValued usermessage = (MultiValued) getMediaArchive().getCachedData("chatterbox", inAgentMessage.get("replytoid"));
		String query = usermessage.get("message");

		Data entity = (Data) messageContext.getContextValue("entity");
		Data entitymodule = (Data) messageContext.getContextValue("entitymodule");

		String text = findSampleOfEmbeddedData(entitymodule, entity);
		messageContext.addContext("embeddedtext", text);

		LlmConnection llmconnection = getMediaArchive().getLlmConnection("thinking");
		LlmResponse response = llmconnection.callStructure(messageContext, agentFn);

		Searcher searcher = getMediaArchive().getSearcher("aisuggestion");

		JSONObject json = response.getResponsePayload();
		Collection suggestions = (Collection) json.get("suggestions");
		for (Iterator iterator = suggestions.iterator(); iterator.hasNext();)
		{
			Map airesponse = (Map) iterator.next();
			Data suggestiondata = searcher.createNewData();
			suggestiondata.setValue("aifunction", "chat_questions_suggestions");
			suggestiondata.setValue("entityid", entity.getId());
			suggestiondata.setValue("entitymoduleid", entitymodule.getId());
			suggestiondata.setName((String) airesponse.get("title"));
			suggestiondata.setValue("prompt", airesponse.get("prompt"));
			searcher.saveData(suggestiondata);
		}
		if (suggestions.isEmpty())
		{
			LlmResponse response2 = llmconnection.renderLocalAction(messageContext, "question_nosuggestions");
			messageContext.setLastResponse(response2);
			return;
		}
		else
		{
			response.setExecAutomationSkill("chat_questions_welcome");
		}
		messageContext.setLastResponse(response);
		return;

	}

	protected LlmResponse searchSystemWide(AgentContext messageContext, String query)
	{
		Schema schema = loadSchema();

		Collection<String> moduleids = schema.getModuleIds();

		HitTracker hits = getMediaArchive().query("modulesearch").put("searchtypes", moduleids).freeform("description", query).exact("entityembeddingstatus", "embedded").search();

		Collection<String> docids = new JSONArray();
		for (Iterator iterator = hits.iterator(); iterator.hasNext();)
		{
			MultiValued doc = (MultiValued) iterator.next();
			String searchtype = doc.get("entitysourcetype");
			String docid = searchtype + "_" + doc.getId();
			docids.add(docid);
		}

		EmbeddingManager embeddings = (EmbeddingManager) getMediaArchive().getBean("embeddingManager");
		LlmResponse response = embeddings.findAnswer(messageContext, docids, query);
		return response;
	}

	public String findSampleOfEmbeddedData(Data inEntityModule, Data inEntity)
	{
		// Should we look for children...
		StringBuffer foundtext = new StringBuffer();

		String mystatus = inEntity.get("entityembeddingstatus");
		if (mystatus == null)
		{
			mystatus = "notembedded";
		}
		if (mystatus != null && "embedded".equals(mystatus))
		{
			String markdown = getMarkdown(inEntity);
			if (markdown != null)
			{
				foundtext.append(markdown);
			}
		}
		Collection detailsviews = getMediaArchive().query("view").exact("moduleid", inEntityModule.getId()).exact("systemdefined", false).cachedSearch();
		for (Iterator iterator = detailsviews.iterator(); iterator.hasNext();)
		{
			Data view = (Data) iterator.next();

			String listid = view.get("rendertable");
			if (listid != null)
			{
				if (getMediaArchive().getSearcher(listid).getDetail("entityembeddingstatus") == null)
				{
					continue;
				}

				GuideStatus status = new GuideStatus();
				status.setSearchType(listid);
				status.setViewData(view);

				HitTracker found = null;
				try
				{
					found = getMediaArchive().query(listid).exact(inEntityModule.getId(), inEntity.getId()).facet("entityembeddingstatus").search();
				}
				catch (Exception e)
				{
					log.debug(inEntityModule + " search error " + inEntity);
					continue;
				}

				for (Iterator iterator2 = found.iterator(); iterator2.hasNext();)
				{
					Data data = (Data) iterator2.next();
					String markdown = getMarkdown(data);
					if (markdown != null)
					{
						foundtext.append(markdown);
					}

					if (foundtext.length() > 2000)
					{
						return foundtext.toString();
					}
				}
			}
		}
		return foundtext.toString();
	}

	protected String getMarkdown(Data data)
	{
		String markdown = data.get("markdowncontent");
		if (markdown == null)
		{
			markdown = data.get("maincontent");
			if (markdown == null)
			{
				markdown = data.get("longcaption");
			}
		}
		if (markdown == null)
		{
			String assetid = data.get("primarymedia");
			Asset asset = getMediaArchive().getEntityManager().getAsset(data);
			if (asset != null)
			{
				markdown = asset.get("longcaption");
			}
		}

		if (markdown == null || markdown.isEmpty())
		{
			markdown = data.getName();
		}
		return markdown;
	}

	/*
	 * protected void handleLlmResponse(AgentContext messageContext, LlmResponse response) { //TODO: Use
	 * IF statements to sort what parsing we need to do. parseSearchParams parseWorkflowParams etc
	 * JSONObject content = response.getResponsePayload();
	 * 
	 * String toolname = (String) content.get("next_step"); //request_type
	 * 
	 * if(toolname == null) { throw new OpenEditException("No type specified in results: " +
	 * content.toJSONString()); }
	 * 
	 * JSONObject details = (JSONObject) content.get("step_details");
	 * 
	 * if(details == null) { throw new OpenEditException("No details specified in results: " +
	 * content.toJSONString()); } if( toolname.equals("conversation")) { JSONObject conversation =
	 * (JSONObject) details.get("conversation"); String generalresponse = (String)
	 * conversation.get("friendly_response");
	 * 
	 * response.setMessage( generalresponse); // response.setFunctionName("conversation"); } else if(
	 * toolname.equals("question_search") ) { JSONObject structure = (JSONObject) details.get(toolname);
	 * if(structure == null) { throw new OpenEditException("No structure found for type: " + toolname);
	 * } String search_keyword = (String) structure.get("search_keyword");
	 * messageContext.setValue("search_keyword", search_keyword); //Search system wise for keyword hits
	 * that are embedded }
	 * 
	 * response.setFunctionName(toolname); }
	 */

	public String getAnswerByEntity(String inModuleId, String inEntityid, String inQuestion)
	{
		AssistantManager assistant = (AssistantManager) getMediaArchive().getBean("assistantManager");
		Collection<String> docIds = assistant.findDocIdsForEntity(inModuleId, inEntityid);
		EmbeddingManager embeddings = (EmbeddingManager) getMediaArchive().getBean("embeddingManager");
		String answer = embeddings.findAnswer(docIds, inQuestion);
		return answer;
	}


}
