package org.entermediadb.ai.skills;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.entermediadb.ai.llm.LlmResponse;
import org.openedit.MultiValued;
import org.openedit.OpenEditException;
import org.openedit.repository.ContentItem;
import org.openedit.util.DataOutputSaver;
import org.openedit.util.Exec;
import org.openedit.util.ExecResult;

/**
 * OpenCodeRunnerSkill - Runs the opencode CLI in a working directory passed in the agent context
 * and appends its output to a temporary file, then returns the file contents as the LLM response
 * message.
 *
 * Usage in AgentContext: - Set "workingpath" (optional) - Directory the opencode command runs in;
 * defaults to one level above getMediaArchive().getRootDirectory() - Set "outputfile" (optional) -
 * Path of the temp file to append output to; if not provided, defaults to a unique log file under
 * workingpath/tomcat/logs/ - Set "yolo" (optional) - If true, passes --yolo to opencode so it runs
 * without permission prompts
 *
 * Results stored in context: - "commandoutput" - Full contents of the output file after running -
 * "outputfilepath" - Path of the temp file that holds the appended output
 */
public class OpenCodeRunnerSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(OpenCodeRunnerSkill.class);

	protected static final String COMMAND = "opencode";

	protected Exec fieldExec;

	@Override
	public void process(AgentContext inContext)
	{

		String query = (String) inContext.getContextValue("userrequest");
		if(  query == null || query.trim().isEmpty())
		{
			throw new OpenEditException("OpenCodeRunnerSkill: No user request provided");
		}

		String workingpath = (String) inContext.getContextValue("workingpath");
		if (workingpath == null || workingpath.trim().isEmpty())
		{
			ContentItem root = getMediaArchive().getPageManager().getRepository().get("/");

			workingpath = new File( root.getAbsolutePath() ).getParentFile().getAbsolutePath();

			log.info("OpenCodeRunnerSkill: No workingpath provided, defaulting to " + workingpath);
		}

		//opencode run --model "local-llama//root/unsloth/Qwen3.8-27B-GGUF/Qwen3.8-27B-UD-Q4_K_XL.gguf" "What is 2 + 3"
		log.info("OpenCodeRunnerSkill running command: " + COMMAND + " in " + workingpath);

		List<String> args = new ArrayList<String>();
		// Add any additional arguments to the command here if needed

		args.add("--dir");
		args.add(workingpath);
		//args.add("--config");
		//args.add(new File(workingpath, "opencode.json").getAbsolutePath());
		args.add("--model");
		args.add("local-llama//root/unsloth/Qwen3.8-27B-GGUF/Qwen3.8-27B-UD-Q4_K_XL.gguf");

		String yolo = (String) inContext.getContextValue("yolo");
		if (yolo == null || Boolean.parseBoolean(yolo))
		{
			args.add("--yolo");
		}

		args.add("run");
		args.add("--format");
		args.add("json");
		args.add(query);

		try
		{

			MultiValued agentjobstep = (MultiValued) inContext.getContextValue("agentjobstep");
			DataOutputSaver dataOutputSaver = new DataOutputSaver(agentjobstep, "lastresponse");
			dataOutputSaver.setCatalogId(getCatalogId());
			dataOutputSaver.setModuleManager(getModuleManager());

			int minutes = 1000 * 60 * 30; //60 second x 20 = 20 minutes
			ExecResult execResult =  getExec().runExec(COMMAND, args, true, new File(workingpath), minutes,
				(String lineX) -> {
					dataOutputSaver.handleLog("INFO", lineX, null);
				});
			dataOutputSaver.flush(); //Saves log to DB

			if( execResult.getReturnValue() == 0)
			{
				log.info("OpenCodeRunnerSkill command executed successfully");
			}
			int exitcode = execResult.getReturnValue();
			String stdout = execResult.getStandardOut();

			log.info("OpenCodeRunnerSkill command exited with code: " + exitcode);
			//String filecontents = stdout;
			inContext.put("commandoutput", stdout);
			
			LlmResponse response = new BasicLlmResponse();
			response.setMessage(stdout);
			inContext.setLastResponse(response);  //Do I really need this?
			if (exitcode != 0)
			{
				inContext.put("errormessage", "Command exited with code " + exitcode);
				throw new OpenEditException("Command exited with code " + exitcode);
			}

		}
		catch (Exception e)
		{
			log.error("OpenCodeRunnerSkill error running command: " + COMMAND + " in " + workingpath, e);
			if (e instanceof InterruptedException)
			{
				Thread.currentThread().interrupt();
			}
			throw new OpenEditException("OpenCodeRunnerSkill error running command: " + COMMAND + " in " + workingpath, e);
		}
		super.process(inContext);
	}

	protected void appendToFile(File inFile, String inContent) throws IOException
	{
		if (inContent == null || inContent.isEmpty())
		{
			return;
		}
		FileOutputStream out = new FileOutputStream(inFile, true);
		try
		{
			out.write(inContent.getBytes("UTF-8"));
		}
		finally
		{
			out.close();
		}
	}

	protected String readFileContents(File inFile, boolean deleteAfterRead)
	{
		FileInputStream in = null;
		try
		{
			in = new FileInputStream(inFile);
			String contents = getExec().getFiller().readAllText(in);
			if (contents == null)
			{
				return "";
			}
			
			return contents;
		}
		catch (IOException e)
		{
			log.error("OpenCodeRunnerSkill failed reading output file: " + inFile.getAbsolutePath(), e);
			return "";
		}
		finally
		{
			getExec().getFiller().close(in);
			if (deleteAfterRead && inFile.exists())
			{
				inFile.delete();
			}
		}
	}

	public Exec getExec()
	{
		return fieldExec;
	}

	public void setExec(Exec inExec)
	{
		fieldExec = inExec;
	}

}
