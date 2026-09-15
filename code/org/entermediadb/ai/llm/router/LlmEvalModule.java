package org.entermediadb.ai.llm.router;

import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.llm.BaseAgentContext;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.ai.llm.openai.OpenAiConnection;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.asset.modules.BaseMediaModule;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.users.User;
import org.openedit.util.JSONParser;

/** Calls exactly one aiserver row for one function, no failover, so the eval harness can compare providers. */
public class LlmEvalModule extends BaseMediaModule
{
	public void evalCall(WebPageRequest inReq)
	{
		User user = inReq.getUser();
		if (user == null || !user.isInGroup("administrators"))
		{
			fail(inReq, 403, "administrators only");
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		String function = inReq.getRequestParameter("function");
		String serverid = inReq.getRequestParameter("aiserver");
		if (function == null || serverid == null)
		{
			fail(inReq, 400, "function and aiserver are required");
			return;
		}
		Data server = archive.getData("aiserver", serverid);
		if (server == null)
		{
			fail(inReq, 400, "unknown aiserver " + serverid);
			return;
		}
		if (Boolean.parseBoolean(server.get("disabled")))
		{
			fail(inReq, 400, "aiserver " + serverid + " is disabled");
			return;
		}

		AgentContext context = new BaseAgentContext();
		context.setCatalogId(archive.getCatalogId());
		context.setModuleManager(archive.getModuleManager());
		context.addContext("mediaarchive", archive);
		String input = inReq.getRequestParameter("input");
		if (input != null && !input.trim().isEmpty())
		{
			try
			{
				JSONObject values = new JSONParser().parse(input);
				for (Object key : values.keySet())
				{
					context.putContextValue((String) key, values.get(key));
				}
			}
			catch (Throwable ex)
			{
				fail(inReq, 400, "bad input json: " + ex.getMessage());
				return;
			}
		}

		LlmConnection connection;
		try
		{
			connection = (LlmConnection) archive.getModuleManager().getBean(archive.getCatalogId(), server.get("connectionbean"), false);
			connection.setAiServerData(server);
		}
		catch (Throwable ex)
		{
			fail(inReq, 500, "could not load connection: " + ex.getMessage());
			return;
		}

		JSONObject out = new JSONObject();
		out.put("aiserver", serverid);
		out.put("function", function);
		out.put("model", server.get("modelname"));

		if ("true".equals(inReq.getRequestParameter("dryrun")))
		{
			try
			{
				context.put("model", server.get("modelname"));
				String rendered;
				if (connection instanceof OpenAiConnection)
				{
					rendered = ((OpenAiConnection) connection).loadCallPayload(context, function).toJSONString();
				}
				else
				{
					String path = "/" + archive.getMediaDbId() + "/ai/" + connection.getLlmProtocol() + "/calls/" + function + ".json";
					rendered = connection.loadInputFromTemplate(context, path);
				}
				out.put("ok", Boolean.TRUE);
				out.put("rendered", rendered);
				reply(inReq, out);
			}
			catch (Throwable ex)
			{
				fail(inReq, 400, ex.getMessage());
			}
			return;
		}

		long start = System.currentTimeMillis();
		try
		{
			LlmResponse response = connection.callStructure(context, function);
			out.put("ok", Boolean.TRUE);
			out.put("message", response.getMessage());
			out.put("payload", response.getResponsePayload());
			if (response.getRawResponse() != null)
			{
				out.put("usage", response.getRawResponse().get("usage"));
			}
		}
		catch (Throwable ex)
		{
			out.put("ok", Boolean.FALSE);
			out.put("error", String.valueOf(ex.getMessage()));
		}
		out.put("ms", System.currentTimeMillis() - start);
		reply(inReq, out);
	}

	protected void reply(WebPageRequest inReq, JSONObject inJson)
	{
		inReq.putPageValue("json", inJson.toJSONString());
	}

	protected void fail(WebPageRequest inReq, int inStatus, String inMessage)
	{
		if (inReq.getResponse() != null)
		{
			inReq.getResponse().setStatus(inStatus);
		}
		JSONObject err = new JSONObject();
		err.put("ok", Boolean.FALSE);
		err.put("error", inMessage);
		reply(inReq, err);
		inReq.setCancelActions(true);
	}
}
