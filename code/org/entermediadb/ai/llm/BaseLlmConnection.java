package org.entermediadb.ai.llm;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.llm.http.HttpResponse;
import org.entermediadb.asset.MediaArchive;
import org.openedit.util.HttpSharedConnection;
import org.openedit.util.JSONParser;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.HttpException;
import org.openedit.ModuleManager;
import org.openedit.OpenEditException;
import org.openedit.WebPageRequest;
import org.openedit.page.Page;
import org.openedit.page.PageStreamer;
import org.openedit.page.manage.PageManager;
import org.openedit.servlet.OpenEditEngine;
import org.openedit.users.User;
import org.openedit.util.OutputFiller;
import org.openedit.util.PathUtilities;
import org.openedit.util.RequestUtils;

public class BaseLlmConnection implements LlmConnection
{
	private static Log log = LogFactory.getLog(LlmConnection.class);

	protected ModuleManager fieldModuleManager;
	protected PageManager fieldPageManager;
	protected RequestUtils fieldRequestUtils;
	protected OutputFiller filler = new OutputFiller();
	protected OpenEditEngine fieldEngine;
	protected Data fieldAiServerData;

	protected HttpSharedConnection fieldConnection;

	protected HttpSharedConnection getConnection()
	{
		if (fieldConnection == null)
		{
			fieldConnection = new HttpSharedConnection();
		}
		return fieldConnection;
	}

	public static final int DEFAULT_TIMEOUT_SECONDS = 30;
	public static final int MAX_TIMEOUT_SECONDS = 1200;

	// ThreadLocal: a routed connection's delegate is shared across concurrent calls, so a
	// per-instance field would let one thread's override leak into another thread's request.
	// The HTTP call happens on the same thread that set the override, so this is enough.
	protected ThreadLocal<Integer> fieldTimeoutOverride = new ThreadLocal<Integer>();

	public void setTimeoutOverride(Integer inSeconds)
	{
		if (inSeconds == null)
		{
			fieldTimeoutOverride.remove();
		}
		else
		{
			fieldTimeoutOverride.set(inSeconds);
		}
	}

	/** Route override, else the aiserver row's timeoutseconds, else 30; never above 1200. */
	public int getTimeoutSeconds()
	{
		int seconds = DEFAULT_TIMEOUT_SECONDS;
		Integer override = fieldTimeoutOverride.get();
		if (override != null)
		{
			seconds = override;
		}
		else if (getAiServerData() != null)
		{
			String value = getAiServerData().get("timeoutseconds");
			if (value != null && !value.trim().isEmpty())
			{
				try
				{
					int parsed = Integer.parseInt(value.trim());
					if (parsed > 0)
					{
						seconds = parsed;
					}
				}
				catch (NumberFormatException ex)
				{
					log.error("Bad timeoutseconds on aiserver " + getAiServerData().getId() + ": " + value);
				}
			}
		}
		return Math.max(1, Math.min(seconds, MAX_TIMEOUT_SECONDS));
	}

	/** Executes with this row's socket timeout instead of HttpSharedConnection's 30 s default. */
	protected CloseableHttpResponse execute(HttpRequestBase inMethod)
	{
		RequestConfig config = RequestConfig.custom()
			.setCookieSpec(CookieSpecs.STANDARD)
			.setConnectionRequestTimeout(5 * 1000)
			.setConnectTimeout(10 * 1000)
			.setSocketTimeout(getTimeoutSeconds() * 1000)
			.build();
		inMethod.setConfig(config);
		return getConnection().sharedExecute(inMethod);
	}

	/** Merges the aiserver row's extraparams JSON object (provider-specific options) into the request. */
	public JSONObject mergeExtraParams(JSONObject inPayload)
	{
		if (getAiServerData() == null)
		{
			return inPayload;
		}
		String extra = getAiServerData().get("extraparams");
		if (extra == null || extra.trim().isEmpty())
		{
			return inPayload;
		}
		// Misconfiguration must not kill the call: bad or non-object extraparams is logged and ignored.
		try
		{
			JSONObject more = new JSONParser().parse(extra);
			inPayload.putAll(more);
		}
		catch (Exception ex)
		{
			log.error("Ignoring invalid extraparams on aiserver " + getAiServerData().getId());
		}
		return inPayload;
	}

	public Data getAiServerData()
	{
		return fieldAiServerData;
	}

	public void setAiServerData(Data fieldMainServerUrl)
	{
		fieldAiServerData = fieldMainServerUrl;
	}

	public String getServerRoot()
	{
		String url = getAiServerData().get("serverroot");

		String serverpathprefix = getAiServerData().get("serverpathprefix");
		if (serverpathprefix != null)
		{
			url = url + serverpathprefix;
		}

		return url;
		// TODO: lookup from new servers table
		// - create llama-vision and llama implementation, they may be in 2 different
		// servers
		// - put the extension part (v1/....) inside each place we call getServerRoot.
		// - cleanup catalogsettings server ids.

	}

	public String getModelName()
	{
		return getAiServerData().get("modelname");
	}

	// public String getLlmProtocol()
	// {
	// return getAiServerData().get("llmprotocol");
	// }

	protected String fieldCatalogId;
	protected MediaArchive fieldMediaArchive;

	public MediaArchive getMediaArchive()
	{
		if (fieldMediaArchive == null)
		{
			fieldMediaArchive = (MediaArchive) getModuleManager().getBean(getCatalogId(), "mediaArchive");
		}
		return fieldMediaArchive;
	}

	public String getCatalogId()
	{
		return fieldCatalogId;
	}

	public void setCatalogId(String inCatalogId)
	{
		fieldCatalogId = inCatalogId;
	}

	public String getApiKey()
	{
		String api = getAiServerData().get("serverapikey");
		return api;
	}

	public ModuleManager getModuleManager()
	{
		return fieldModuleManager;
	}

	public void setModuleManager(ModuleManager inModuleManager)
	{
		fieldModuleManager = inModuleManager;
	}

	public BaseLlmConnection() {
		super();
	}

	public PageManager getPageManager()
	{
		return fieldPageManager;
	}

	public void setPageManager(PageManager inPageManager)
	{
		fieldPageManager = inPageManager;
	}

	public RequestUtils getRequestUtils()
	{
		return fieldRequestUtils;
	}

	public void setRequestUtils(RequestUtils inRequestUtils)
	{
		fieldRequestUtils = inRequestUtils;
	}

	public OpenEditEngine getEngine()
	{
		if (fieldEngine == null)
		{
			fieldEngine = (OpenEditEngine) getModuleManager().getBean("OpenEditEngine");

		}

		return fieldEngine;
	}

	public Boolean isReady()
	{
		if (getApiKey() == null || getApiKey().length() == 0)
		{
			log.error("No apikey defined in catalog settings");
			return false;
		}
		return true;
	}

	public String loadInputFromTemplate(AgentContext agentcontext, String inTemplate)
	{
		Map<String, Object> inContext = agentcontext.getAllContext();
		if (inTemplate == null)
		{
			throw new OpenEditException("Cannot load input, template is null" + inContext);
		}
		try
		{
			Page template = getPageManager().getPage(inTemplate);
			log.info("Loading input: " + inTemplate);

			User user = getMediaArchive().getUserManager().getUser("agent");

			WebPageRequest request = getRequestUtils().createPageRequest(template, user);

			if (agentcontext != null)
			{
				loadAgentContextParameters(agentcontext, request);
			}

			request.putPageValues(inContext);

			StringWriter output = new StringWriter();
			request.setWriter(output);
			PageStreamer streamer = getEngine().createPageStreamer(template, request);

			getEngine().executePathActions(request);
			if (!request.hasRedirected())
			{
				getModuleManager().executePageActions(template, request);
			}
			if (request.hasRedirected())
			{
				log.info("action was redirected");
			}

			streamer.include(template, request);
			String string = output.toString();
			// log.info(inTemplate +" Output: " + string);
			return string;
		}
		catch (OpenEditException e)
		{
			throw e;
		}
	}

	protected void loadAgentContextParameters(AgentContext agentcontext, WebPageRequest request)
	{
		request.putPageValue("sessionlocale", agentcontext.getLocale());

		Map inParameters = agentcontext.getProperties();
		for (Iterator iterator = inParameters.keySet().iterator(); iterator.hasNext();)
		{
			String key = (String) iterator.next();
			Object obj = inParameters.get(key);
			if (obj instanceof String)
			{
				request.setRequestParameter(key, (String) obj);
			}
			else
			{
				if (obj instanceof JSONObject)
				{
					JSONObject json = (JSONObject) obj;
					request.setRequestParameter(key, json.toJSONString());
				}
				else
				{
					if (obj instanceof Collection)
					{
						Collection<String> col = (Collection<String>) obj;
						obj = (String[]) col.toArray(new String[col.size()]);
						request.setRequestParameter(key, (String[]) obj);
					}
					else
					{
						if (obj instanceof String[])
						{
							request.setRequestParameter(key, (String[]) obj);
						}
					}
				}
			}
		}
		Map pagevalues = agentcontext.getAllContext();
		if (pagevalues != null)
		{
			for (Iterator iterator = pagevalues.keySet().iterator(); iterator.hasNext();)
			{
				String key = (String) iterator.next();
				Object obj = pagevalues.get(key);
				if (obj instanceof String)
				{
					request.setRequestParameter(key, (String) obj);
				}
			}
		}
	}

	public LlmResponse renderLocalAction(AgentContext agentcontext, String inTemplateName)
	{
		String apphome = "/" + getMediaArchive().getCatalogSettingValue("mediadbappid");

		String templatepath = apphome + "/views/agentresponses/" + inTemplateName + ".html";

		try
		{
			Page template = getPageManager().getPage(templatepath);

			if (!template.exists())
			{
				templatepath = "/mediadb/views/agentresponses/" + inTemplateName + ".html";
				template = getPageManager().getPage(templatepath);
			}

			User user = getMediaArchive().getUserManager().getUser("agent");

			WebPageRequest inReq = getRequestUtils().createPageRequest(template, user);
			inReq.putPageValues(agentcontext.getAllContext());
			inReq.putPageValue("agentcontext", agentcontext);
			loadAgentContextParameters(agentcontext, inReq);

			StringWriter output = new StringWriter();
			inReq.setWriter(output);

			PageStreamer streamer = getEngine().createPageStreamer(template, inReq);
			getEngine().executePathActions(inReq);
			if (!inReq.hasRedirected())
			{
				getModuleManager().executePageActions(template, inReq);
			}
			if (inReq.hasRedirected())
			{
				log.info("action was redirected");
			}

			streamer.include(template, inReq);

			String string = output.toString();
			log.info("Loading response for function: " + inTemplateName + " Output: " + string);

			BasicLlmResponse response = new BasicLlmResponse();

			response.setRawMessage(string);

			return response;
		}
		catch (OpenEditException e)
		{
			throw e;
		}
	}

	public int copyData(JSONObject source, Data data)
	{
		int i = 0;
		Map metadata = (Map) source.get("metadata");
		for (Iterator iterator = metadata.keySet().iterator(); iterator.hasNext();)
		{
			String key = (String) iterator.next();
			Object value = metadata.get(key);

			data.setValue(key, value);
			i++;
		}
		return i;
	}

	// protected JSONObject handleApiRequest(String payload)
	// {
	// String endpoint = getServerRoot();
	// HttpPost method = new HttpPost(endpoint);
	// method.addHeader("Authorization", "Bearer " + getApiKey());
	// method.setHeader("Content-Type", "application/json");
	// method.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));
	//
	//
	// try
	// {
	// if (resp.getStatusLine().getStatusCode() != 200)
	// {
	// log.info("AI Server error status: " + resp.getStatusLine().getStatusCode());
	// log.info("AI Server error response: " + resp.toString());
	// try
	// {
	// String error = EntityUtils.toString(resp.getEntity(),
	// StandardCharsets.UTF_8);
	// log.info(error);
	// }
	// catch(Exception e)
	// {}
	// throw new OpenEditException("handleApiRequest error: " +
	// resp.getStatusLine());
	// }
	//
	// JSONObject json = (JSONObject) connection.parseMap(resp);
	//
	// log.info("returned: " + json.toJSONString());
	//
	// return json;
	// }
	// catch (Exception ex)
	// {
	// log.error("Error calling handleApiRequest", ex);
	// throw new OpenEditException(ex);
	// }
	// finally
	// {
	// connection.release(resp);
	// }
	// }

	@Override
	public LlmResponse createImage(String inPrompt)
	{
		throw new OpenEditException("Model doesn't support images");
	}

	@Override
	public LlmResponse createImage(String inPrompt, int imagecount, String inSize)
	{
		throw new OpenEditException("Model doesn't support images");
	}

	protected Map<String, String> fieldSharedHeaders = null;

	public Map<String, String> getSharedHeaders()
	{
		if (fieldSharedHeaders == null)
		{
			fieldSharedHeaders = new HashMap();
		}

		return fieldSharedHeaders;
	}

	@Override
	public LlmResponse callJson(String inPath, Map inPayload)
	{
		JSONObject json = new JSONObject(inPayload);
		return callJson(inPath, json);

	}

	@Override
	public LlmResponse callJson(String inPath, JSONObject inPayload)
	{
		LlmResponse res = callJson(inPath, getSharedHeaders(), inPayload);
		return res;

	}

	/** Same non-auth headers callJson applies to every eMe-to-LLM request: x-customerkey (falls back to "demo") plus any shared/extra headers. */
	protected void applyLlmHeaders(HttpRequestBase inMethod, Map<String, String> inHeaders)
	{
		String customerkey = getMediaArchive().getCatalogSettingValue("catalog-storageid");
		if (customerkey == null)
		{
			customerkey = "demo";
		}

		inMethod.setHeader("x-customerkey", customerkey); // standard eMedia header

		Map<String, String> shared = getSharedHeaders();
		for (Iterator iterator = shared.keySet().iterator(); iterator.hasNext();)
		{
			String key = (String) iterator.next();
			String value = shared.get(key);
			if (value != null)
			{
				inMethod.setHeader(key, value);
			}
		}

		if (inHeaders != null)
		{
			for (Iterator iterator = inHeaders.keySet().iterator(); iterator.hasNext();)
			{
				String key = (String) iterator.next();
				String value = inHeaders.get(key);
				if (value != null)
				{
					inMethod.setHeader(key, value);
				}
			}
		}
	}

	@Override
	public LlmResponse callJson(String inPath, Map<String, String> inHeaders, JSONObject inPayload)
	{
		// log.info("Calling LLM Server at: " + getServerRoot() + inPath); //Log this
		// from the function call
		HttpRequestBase method = null;

		if (inPayload == null)
		{
			method = new HttpGet(getServerRoot() + inPath);
		}
		else
		{
			method = new HttpPost(getServerRoot() + inPath);
		}

		method.addHeader("Authorization", "Bearer " + getApiKey());
		method.setHeader("Content-Type", "application/json");

		applyLlmHeaders(method, inHeaders);

		if (method instanceof HttpPost)
		{
			((HttpPost) method).setEntity(new StringEntity(inPayload.toJSONString(), StandardCharsets.UTF_8));
		}
		HttpSharedConnection connection = getConnection();
		CloseableHttpResponse resp = execute(method);
		Object object = null;
		try
		{
			/*
			 * if (resp.getStatusLine().getStatusCode() == 400) { getSharedConnection().release(resp);
			 * log.info("Face detection Remote Error on asset: " + inAsset.getId() + " " +
			 * resp.getStatusLine().toString() ) ; inAsset.setValue("facescanerror", true); return
			 * Collections.EMPTY_LIST; } else if (resp.getStatusLine().getStatusCode() == 413) { //remote error
			 * body size getSharedConnection().release(resp);
			 * log.info("Face detection Remote Body Size Error on asset: " + inAsset.getId() + " " +
			 * resp.getStatusLine().toString() ) ; inAsset.setValue("facescanerror", true); return null; } else
			 * if (resp.getStatusLine().getStatusCode() == 500) { //remote server error, may be a broken image
			 * getSharedConnection().release(resp); log.info("Face detection Remote Error on asset: " +
			 * inAsset.getId() + " " + resp.getStatusLine().toString() ) ; inAsset.setValue("facescanerror",
			 * true); return null; }
			 */

			if (resp.getStatusLine().getStatusCode() != 200)
			{
				log.info("Error: " + getServerRoot() + inPath + " returned status code: " + resp.getStatusLine().getStatusCode());
				log.info("Error response: " + resp.toString());
				String error = null;
				try
				{
					error = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
				}
				catch (Exception e)
				{
					// Ignore
				}
				if (error == null)
				{
					error = "Could not call " + inPath + " - Status: " + resp.getStatusLine().toString();
				}
				log.info(error);

				HttpException ex = new HttpException(error, inPath, resp.getStatusLine().getStatusCode());

			}

			object = connection.parseJson(resp);
		}
		finally
		{
			connection.release(resp);
		}
		LlmResponse response = createResponse();

		if (object instanceof JSONObject)
		{
			response.setRawResponse((JSONObject) object);
		}
		else
		{
			response.setRawCollection((JSONArray) object);
		}

		return response;
	}

	@Override
	public LlmResponse callJson(String inPath, Map<String, String> inHeaders, Map inMap)
	{
		HttpSharedConnection connection = getConnection();

		if (inHeaders != null)
		{
			for (Iterator iterator = inHeaders.keySet().iterator(); iterator.hasNext();)
			{
				String key = (String) iterator.next();
				String value = inHeaders.get(key);
				connection.addSharedHeader(key, value); // Todo: Pass in heades in the parameters
			}
		}

		CloseableHttpResponse resp = connection.sharedMimePost(getServerRoot() + inPath, inMap);
		Object object = null;
		try
		{
			if (resp.getStatusLine().getStatusCode() != 200)
			{
				log.info("Error: " + getServerRoot() + inPath + " returned status code: " + resp.getStatusLine().getStatusCode());
				log.info("Error response: " + resp.toString());
				try
				{
					String error = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
					log.info(error);
				}
				catch (Exception e)
				{
					// Ignore
				}
				throw new OpenEditException("Could not call " + inPath);
			}
			object = connection.parseJson(resp);
		}
		finally
		{
			connection.release(resp);
		}
		HttpResponse response = new HttpResponse();
		if (object instanceof JSONObject)
		{
			response.setRawResponse((JSONObject) object);
		}
		else
		{
			response.setRawCollection((JSONArray) object);
		}

		return response;
	}

	@Override
	public String getLlmProtocol()
	{
		throw new OpenEditException("Call not supported");
	}

	@Override
	public LlmResponse callCreateFunction(AgentContext inAgentcontext, String inFunction)
	{
		throw new OpenEditException("Call not supported");
	}

	@Override
	public LlmResponse callClassifyFunction(AgentContext inAgentcontext, String inFunction, String inBase64Image)
	{
		throw new OpenEditException("Call not supported");
	}

	@Override
	public LlmResponse callClassifyFunction(AgentContext inAgentcontext, String inFunction, String inBase64Image, String inTextContent)
	{
		throw new OpenEditException("Call not supported");
	}

	@Override
	public LlmResponse callToolsFunction(AgentContext inAgentContext, String inFunction)
	{
		throw new OpenEditException("Call not supported");
	}

	@Override
	public LlmResponse runPageAsInput(AgentContext inLlmRequest, String inChattemplate)
	{
		throw new OpenEditException("Call not supported");
	}

	@Override
	public LlmResponse callStructure(AgentContext inAgentcontext, String inFuction)
	{
		throw new OpenEditException("Call not supported");
	}

	@Override
	public LlmResponse callOCRFunction(AgentContext inAgentcontext, String inBase64Image, String inFunctioName)
	{
		throw new OpenEditException("Call not supported");
	}

	@Override
	public LlmResponse callSmartCreatorAiAction(AgentContext inAgentcontext, String inActionName)
	{
		throw new OpenEditException("Call not supported");
	}

	@Override
	public LlmResponse createResponse()
	{
		throw new OpenEditException("Call not supported");
	}

}
