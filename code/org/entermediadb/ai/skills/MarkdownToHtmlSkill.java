package org.entermediadb.ai.skills;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.BaseSkill;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.entermediadb.markdown.MarkdownUtil;

public class MarkdownToHtmlSkill extends BaseSkill
{
	private static final Log log = LogFactory.getLog(MarkdownToHtmlSkill.class);

	@Override
	public void process(AgentContext inAgentContext)
	{
		String markdowncontent = (String) inAgentContext.getContextValue("markdowncontent");
		if (markdowncontent == null)
		{
			log.warn("MarkdownToHtmlSkill: no 'markdowncontent' parameter found in context");
			inAgentContext.put("html", "");
			//super.process(inAgentContext);
			return;
		}

		MarkdownUtil markdown = new MarkdownUtil();
		String html = markdown.render(markdowncontent);
		log.info("MarkdownToHtmlSkill: rendered " + markdowncontent.length() + " chars of markdown to " + (html != null ? html.length() : 0) + " chars of html");

		inAgentContext.put("markdown", markdown);
		inAgentContext.put("html", html);

		BasicLlmResponse response = new BasicLlmResponse();
		response.setMessage(html);
		inAgentContext.setLastResponse(response);

		super.process(inAgentContext);
	}

}
