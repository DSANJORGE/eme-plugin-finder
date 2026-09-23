package org.entermediadb.ai.skills;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;

public class HandleErrorSkill extends BaseSkill
{
    private static final Log log = LogFactory.getLog(HandleErrorSkill.class);
    
    @Override
    public void process(AgentContext inAgentContext)
    {
        // inAgentContext.addContext("error", inError);
		// inAgentContext.addContext("errorcode", inCode);
		LlmConnection llmconnection = getMediaArchive().getLlmConnection("localrender");
		LlmResponse response = llmconnection.renderLocalAction(inAgentContext, "render_error");
		// inAgentContext.setFunctionName(null);
		response.setExecAutomationSkill(null);
		inAgentContext.setLastResponse(response);

        //TODO: Return to start
        

        AutomationStep skillEnabled = inAgentContext.getCurrentAutomationStep();
		if( skillEnabled == null )
		{
            log.error("No current automation step found in context. Cannot continue after error.");
			return;
		}

		inAgentContext.fireStatusComplete(skillEnabled);
    }

}
