package org.entermediadb.mcp.client;
import java.util.Date;
import org.json.simple.JSONObject;
import org.openedit.Data;
public class SessionStatus
{

    protected String fieldCurrentQuestion;

    public String getCurrentQuestion() {
        return fieldCurrentQuestion;
    }

    public void setCurrentQuestion(String currentQuestion) {
        this.fieldCurrentQuestion = currentQuestion;
    }

    protected String fieldSessionId;
    public String getSessionId() {
        return fieldSessionId;
    }

    public void setSessionId(String sessionId) {
        this.fieldSessionId = sessionId;
    }
    protected Date fieldStartDate;

    public Date getStartDate() {
        return fieldStartDate;
    }

    public void setStartDate(Date startDate) {
        this.fieldStartDate = startDate;
    }   

    protected Data  fieldAgentJobStep;
    public Data getAgentJobStep() {
        return fieldAgentJobStep;
    }

    public void setAgentJobStep(Data agentJobStep) {
        this.fieldAgentJobStep = agentJobStep;
    }

    boolean fieldCompleted;

    public boolean isCompleted() {
        return fieldCompleted;
    }

    public void setCompleted(boolean completed) {
        this.fieldCompleted = completed;
    }

    // Sequence number (OpenCodeClient's event counter) of the last /event we've applied to this
    // session, so repeated polls only process events that arrived since the previous poll.
    protected long fieldLastEventSeq;

    public long getLastEventSeq() {
        return fieldLastEventSeq;
    }

    public void setLastEventSeq(long lastEventSeq) {
        this.fieldLastEventSeq = lastEventSeq;
    }

    // Id of an outstanding opencode "permission.updated" event (a tool call awaiting approval)
    // that currentQuestion is describing. Null once answered (permission.replied) or never asked.
    protected String fieldPendingPermissionId;

    public String getPendingPermissionId() {
        return fieldPendingPermissionId;
    }

    public void setPendingPermissionId(String pendingPermissionId) {
        this.fieldPendingPermissionId = pendingPermissionId;
    }

    // Job status the pending request maps to: "securityprompt" (permission.asked) or "question"
    // (form.created). Only meaningful while getPendingPermissionId() is not null.
    protected String fieldPendingStatus;

    public String getPendingStatus() {
        return fieldPendingStatus;
    }

    public void setPendingStatus(String pendingStatus) {
        this.fieldPendingStatus = pendingStatus;
    }

    // The opencode Form.Info ({id, sessionID, title, metadata?, fields:[...]}) from form.created,
    // while that form is waiting on an answer. Null for permission requests.
    protected JSONObject fieldPendingForm;

    public JSONObject getPendingForm() {
        return fieldPendingForm;
    }

    public void setPendingForm(JSONObject pendingForm) {
        this.fieldPendingForm = pendingForm;
    }

    protected String fieldError;

    public String getError() {
        return fieldError;
    }

    public void setError(String error) {
        this.fieldError = error;
    }

}
