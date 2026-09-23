package org.entermediadb.ai.llm;

import java.util.ArrayList;
import java.util.Collection;

import org.json.simple.JSONObject;

public interface LlmResponse
{

    JSONObject getResponsePayload();

    JSONObject getToolsResponse();

    JSONObject getRawResponse();

    void setRawResponse(JSONObject inObj);

    Collection getRawCollection();

    void setRawCollection(Collection inObj);

    Collection getCollection(String inKey);

    boolean isToolCall();

    String getMessage();

    void setMessage(String inMessage);

    String getMessagePlain();

    void setMessagePlain(String inMessagePlain);

    void setRawMessage(String inMessage);

    String getExecAutomationSkill();

    void setExecAutomationSkill(String inFunction);

    String getNextAutomationStep();

    void setNextAutomationStep(String inFunction);

    JSONObject getFunctionArguments();

    boolean isSuccessful();

    int getTokensUsed();

    String getModel();

    ArrayList<String> getImageUrls();

    ArrayList<String> getImageBase64s();

    String getFileName();

    void setOperationState(String inOperationState); // error, cancel, continue, runskill

    String getOperationState(); // error, cancel, continue, runskill
}
