package org.entermediadb.ai.llm;

import java.util.ArrayList;
import java.util.Collection;

import org.entermediadb.ai.assistant.AiSearch;
import org.entermediadb.ai.knn.RankedResult;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class BasicLlmResponse implements LlmResponse
{
	protected String fieldMessage;
	protected String fieldMessagePlain;
	protected String fieldRunFunctionName;
	protected String fieldNextFunctionName;
	protected String fieldOperationState; // error, cancel, continue, runskill
	protected JSONObject fieldFunctionArguments;
	protected AiSearch fieldAiSearchParams;
	Collection<RankedResult> fieldRankedSuggestions;

	public Collection<RankedResult> getRankedSuggestions()
	{
		return fieldRankedSuggestions;
	}

	public void setRankedSuggestions(Collection<RankedResult> inRankedSuggestions)
	{
		fieldRankedSuggestions = inRankedSuggestions;
	}

	public AiSearch getAiSearchParams()
	{
		return fieldAiSearchParams;
	}

	public void setAiSearchParams(AiSearch inAiSearchParams)
	{
		fieldAiSearchParams = inAiSearchParams;
	}

	@Override
	public String getMessage()
	{

		return fieldMessage;

	}
		@Override
	public void setMessage(String inMessage)
	{
		fieldMessage = inMessage;
	}



	public void setMessagePlain(String inMessage)
	{
		fieldMessagePlain = inMessage;
	}

	@Override
	public String getMessagePlain()
	{
		return fieldMessagePlain;
	}

	protected Collection fieldRawCollection;

	public Collection getRawCollection()
	{
		return fieldRawCollection;
	}

	public void setRawCollection(JSONArray inRawCollection)
	{
		fieldRawCollection = inRawCollection;
	}

	protected JSONObject rawResponse;

	public JSONObject getRawResponse()
	{
		return rawResponse;
	}

	public void setRawResponse(JSONObject inRawResponse)
	{
		rawResponse = inRawResponse;
	}

	@Override
	public boolean isToolCall()
	{
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public JSONObject getResponsePayload()
	{
		return getRawResponse();
	}

	public JSONObject getToolsResponse()
	{
		return getResponsePayload();
	}

	@Override
	public String getExecAutomationSkill()
	{
		return fieldRunFunctionName;
	}

	public void setExecAutomationSkill(String inFunctionName)
	{
		fieldRunFunctionName = inFunctionName;
	}

	@Override
	public boolean isSuccessful()
	{
		// TODO Auto-generated method stub
		return false;
	}

	// Are these needed?

	@Override
	public int getTokensUsed()
	{
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public String getModel()
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ArrayList<String> getImageUrls()
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ArrayList<String> getImageBase64s()
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getFileName()
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setRawCollection(Collection inObj)
	{
		fieldRawCollection = inObj;
	}

	@Override
	public Collection getCollection(String inKey)
	{
		Object obj = getResponsePayload().get(inKey);
		if (obj instanceof JSONArray || obj instanceof Collection)
		{
			return (Collection) obj;
		}
		return null;
	}

	@Override
	public JSONObject getFunctionArguments()
	{
		if (fieldFunctionArguments != null)
		{
			return fieldFunctionArguments;
		}
		return getResponsePayload();
	}

	public void setNextAutomationStep(String inFunction)
	{
		fieldNextFunctionName = inFunction;
	}

	public String getNextAutomationStep()
	{
		return fieldNextFunctionName;
	}

	public void setOperationState(String inOperationState)
	{
		fieldOperationState = inOperationState;
	}

	public String getOperationState()
	{
		return fieldOperationState;
	}

	public void setRawMessage(String inMessage)
	{
		String dataMessage = "";
		String mainMessage = inMessage;

		// refactor: include only messageplain with new lines in between, and remove all other messageplain
		// tags. This is to avoid including messageplain that are part of the main message.
		int dataStart = mainMessage.indexOf("<messageplain>");
		while (dataStart >= 0)
		{
			int dataEnd = mainMessage.indexOf("</messageplain>");
			if (dataEnd <= dataStart)
			{
				break;
			}
			String dm = mainMessage.substring(dataStart + 14, dataEnd).trim();
			if (!dm.isEmpty())
			{
				dataMessage += dm + " \n ";
			}
			mainMessage = mainMessage.substring(0, dataStart).trim() + mainMessage.substring(dataEnd + 15).trim();
			dataStart = mainMessage.indexOf("<messageplain>");
		}

		mainMessage = mainMessage.replaceAll("(?s)<messageplain>.*?</messageplain>", "");
		setMessage(mainMessage);

		if (dataMessage.length() > 0 && getMessagePlain() == null)
		{
			setMessagePlain(dataMessage.trim());
		}
	}
}
