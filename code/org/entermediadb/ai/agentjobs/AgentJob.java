package org.entermediadb.ai.agentjobs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.asset.util.JsonUtil;
import org.openedit.CatalogEnabled;
import org.openedit.ModuleManager;
import org.openedit.MultiValued;
import org.openedit.data.BaseData;
import org.openedit.util.JSONParser;

public class AgentJob extends BaseData implements CatalogEnabled
{

	String fieldCatalogId;

	@Override
	public void setCatalogId(String inId)
	{
		fieldCatalogId =inId;	
	}
	ModuleManager fieldModuleManager;
	
	public void setModuleManager(ModuleManager inModuleManager)
	{
		fieldModuleManager = inModuleManager;
	}	


    private Collection<MultiValued> steps;

	public void addStep(MultiValued step)
	{
		getSteps().add(step);
	}

    public Collection<MultiValued> getSteps()
	{
		if( steps == null)
		{
			steps = getMediaArchive().query("agentjobstep").exact("agentjob", getId()).sort("orderUp").search();
		}
		return steps;
	}


	public MediaArchive getMediaArchive()
	{
		return (MediaArchive) fieldModuleManager.getBean(fieldCatalogId, "mediaArchive");
	}

	public void setSteps(Collection<MultiValued> inSteps)
	{
		steps = inSteps;
	}

	public String findLastResponse()
	{
		steps = null; //Pull from database
		ArrayList<MultiValued> reversed = new ArrayList<MultiValued>(getSteps());
		java.util.Collections.reverse(reversed);
		for(MultiValued step : reversed)
		{
			MultiValued refreshedstep = (MultiValued) getMediaArchive().getCachedData("agentjobstep", step.getId());
			String lastresponse = refreshedstep.get("lastresponse");
			if(lastresponse != null)
			{
				return lastresponse;
			}
		}	
		return null;
	}

/*

"type":"step_start","timestamp":1789512487122,"sessionID":"ses_f58be861affeXndBz5ovsVDMms","part":{"id":"prt_0a74190c5001wE9mE0Hfpbe1BO","messageID":"msg_0a7417e7a0016KFWxQ4PREnns0","sessionID":"ses_f58be861affeXndBz5ovsVDMms","snapshot":"c38236e41ebdd89818fa03d8f43b3c3ec4774c22","type":"step-start"}}
{"type":"tool_use","timestamp":1789512488222,"sessionID":"ses_f58be861affeXndBz5ovsVDMms","part":{"type":"tool","tool":"bash","callID":"6Xe5SVwnIIZqL25tCLdmVOcYv1zqgDrS","state":{"status":"completed","input":{"command":"ls -la bin/"},"output":"total 76\ndrwxrwxr-x  4 shanti shanti 4096 Sep 15 12:02 .\ndrwxrwxr-x 11 shanti shanti 4096 Sep 15 12:02 ..\n-rwxrwxr-x  1 shanti shanti  182 Aug 10 11:13 bash.sh\ndrwxrwxr-x  2 shanti shanti 4096 Aug 13 10:45 build\n-rwxrwxr-x  1 shanti shanti  746 Aug 12 12:30 compile.sh\n-rwxrwxr-x  1 shanti shanti 9661 Sep 15 12:02 eme
*/

	public String summaryLog()
	{
		String json = findLastResponse();
		if( json == null )
		{
			return null;
		}
		//Only include json that that starts  with { and ends with }
		StringBuffer cleanJson = new StringBuffer();

		json.lines().forEach(line -> {
			if(line.startsWith("{") && line.endsWith("}")) {
				cleanJson.append(line);
				cleanJson.append("\n");
			}
		});

		Collection<Map> parsed = new JSONParser().parseCollection("[" + cleanJson.toString() + "]");

		StringBuffer clean  = new StringBuffer();

		for(Map map : parsed)
		{
			// Process each map as needed
			String type = (String) map.get("type");
			if( type.equals("tool_use"))
			{
				Map input = (Map) JsonUtil.getObjectFromMaps("part.state.input", map);
				String stringoutput = (String) JsonUtil.getObjectFromMaps("part.state.output", map);
				clean.append("**Input:**\n").append(input.toString()).append("\n");	
				clean.append("**Output:**\n").append(stringoutput).append("\n");
			}
		}

		return clean.toString();
	}
}
