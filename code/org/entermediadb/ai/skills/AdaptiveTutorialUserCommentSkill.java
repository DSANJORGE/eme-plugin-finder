package org.entermediadb.ai.skills;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.AgentContext;
import org.entermediadb.ai.TutorMessageContext;
import org.entermediadb.ai.llm.AutomationStep;
import org.entermediadb.ai.llm.BasicLlmResponse;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.ai.llm.LlmResponse;
import org.entermediadb.asset.Asset;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.openedit.Data;
import org.openedit.MultiValued;

public class AdaptiveTutorialUserCommentSkill extends AdaptiveTutorialBaseSkill
{
	private static final Log log = LogFactory.getLog(AdaptiveTutorialUserCommentSkill.class);

	// TestU local patch: the hosted embedding server's /chat takes ~14 s to fail
	// on every call (non-JSON reply); after a failure, skip it for a while so
	// the learner is not charged that wait on every question.
	// ponytail: one JVM-wide breaker; per-tutorial breakers if servers differ.
	// Starts closed: the 09-04 failures were llamat's outage, not the payload.
	private static volatile long embedFailedAt = 0;
	private static final long EMBED_RETRY_MS = 30 * 60 * 1000;

	@Override
	public void process(AgentContext inAgentContext)
	{
		TutorMessageContext tutorMessageContext = (TutorMessageContext) inAgentContext;

		String channelid = tutorMessageContext.getChannel().getId();
		String tutorialid = (String) tutorMessageContext.getContextValue("tutorialid");
		String sectionid = (String) tutorMessageContext.getContextValue("sectionid");
		String usermessage = (String) tutorMessageContext.getUserMessage().get("message");
		if (usermessage == null || usermessage.length() == 0)
		{
			// TestU app follow-ups carry the text as context_query only.
			usermessage = (String) tutorMessageContext.getContextValue("query");
		}
		String userId = tutorMessageContext.getUserProfile().getUser().getId();

		LlmConnection llmconnection = getMediaArchive().getLlmConnection("embedding");

		JSONArray chatHistory = getReleaventChatHistory(sectionid, channelid, userId);

		Collection<String> parentIds = getAssistantManager().findDocIdsForEntity("entitytutorial", tutorialid);

		String answer = null;
		String embedkey = llmconnection.getApiKey();
		if (!parentIds.isEmpty() && (embedkey == null || embedkey.isEmpty() || "YOUR_SECRET_TOKEN".equals(embedkey)))
		{
			// No real key: /chat answers "Invalid customer key" or hangs 30s before the fallback below.
			log.info("Skipping embedding server /chat: no API key configured for the embed server");
		}
		else if (!parentIds.isEmpty() && System.currentTimeMillis() - embedFailedAt < EMBED_RETRY_MS)
		{
			log.info("Skipping embedding server /chat: failed " + (System.currentTimeMillis() - embedFailedAt) / 1000 + "s ago");
		}
		else if (!parentIds.isEmpty())
		{
			Map payload = new HashMap();
			payload.put("query", usermessage);
			payload.put("parent_ids", parentIds);
			payload.put("chat_history", chatHistory);

			log.info("Sending /chat to embedding server with query: " + usermessage + ", parent_ids: " + parentIds.size() + ", history size: " + chatHistory.size());
			log.info("Chat payload: " + payload);
			try
			{
				LlmResponse res = llmconnection.callJson("/chat", payload);
				JSONObject contentsJson = res.getRawResponse();
				answer = (String) contentsJson.get("answer");
				if (answer != null && answer.equalsIgnoreCase("Empty Response"))
				{
					answer = null;
				}
				else if (answer != null)
				{
					answer = answer + citeFromSources((JSONArray) contentsJson.get("sources"), usermessage, answer);
				}
			}
			catch (Exception e)
			{
				// TestU local patch: the hosted embedding server answers /save but its
				// /chat backend can be down ("Connection error."); fall back to the
				// local tutor call with keyword-retrieved excerpts instead of failing.
				log.error("Embedding server /chat failed for tutorial " + tutorialid + ", answering from local excerpts", e);
				embedFailedAt = System.currentTimeMillis();
			}
		}

		if (answer == null)
		{
			// TestU local patch: the tutorial has no embedded source documents (or
			// the embedding server failed), so answer from the tutorial context —
			// section content, the question with its rationale, the learner's
			// answer/confidence (context_selectedoption / context_confidence from
			// the app) and keyword-matched excerpts of the reference documents —
			// instead of dropping the question or replying "no sources".
			log.info("Answering from tutorial context for tutorial " + tutorialid);
			// The learner's answer and the question in play: only what THIS
			// request carried (the app's context_* ride on its system message as
			// agentcontextvalues). The channel context keeps the last session
			// answer around, so free chat from the tutor tab or a document was
			// told "your last answer, option A, is wrong" (2026-09-04).
			String requestStr = tutorMessageContext.getUserMessage() == null ? null : tutorMessageContext.getUserMessage().get("agentcontextvalues");
			JSONObject request = null;
			try
			{
				JSONParser parser = new JSONParser();
				request = (JSONObject) parser.parse(requestStr);
			}
			catch (Exception e)
			{
				log.error("Failed to parse request values", e);
			}
			String selected = requestValue(tutorMessageContext, request, "selectedoption");
			String confidence = requestValue(tutorMessageContext, request, "confidence");
			// TestU voice mode (context_voice=true on a voice turn): the template asks for a spoken
			// variant too. Set on every call, a replayed value from the channel context would stick.
			boolean voice = "true".equals(requestValue(tutorMessageContext, request, "voice"));
			tutorMessageContext.putContextValue("voice", Boolean.valueOf(voice));
			String prompt = usermessage;
			if (selected != null && selected.length() > 0)
			{
				// Built here, not in the template: Velocity chokes on a directive
				// at the start of a string literal.
				prompt = "Answer of the learner: " + selected + "\nConfidence: " + confidence + "\nMessage of the learner: " + usermessage;
			}
			String questionid = requestValue(tutorMessageContext, request, "questionid");
			String history = answerHistory(userId, questionid, tutorialid, sectionid);
			if (history.length() > 0)
			{
				prompt = history + "\n" + prompt;
			}
			// The section history lists every question of the section with no
			// marker for the current one; name it explicitly or the model picks
			// the last one it saw.
			Data question = questionid == null ? null : getMediaArchive().getData("entityquestion", questionid);
			// The page the learner is looking at in the PDF viewer comes first, whatever the keywords match.
			// TestU local patch: the pages sent in THIS call ("Title|N" and "Title" for a
			// video's m:ss citation) — any citation of another page came from the "Recent
			// conversation" turns, not from reading, and is dropped below. The value is the
			// text the model read under that heading: quoteForCitation quotes from it.
			Map<String, String> sent = new LinkedHashMap<String, String>();
			String viewed = viewedPage(requestValue(tutorMessageContext, request, "entityasset"), requestValue(tutorMessageContext, request, "pagenum"), sent);
			// A session follow-up ("¿Por qué las otras opciones están mal?") has no keywords of its own: the
			// question's text and its correct option find the pages the learner's words cannot.
			String questiontext = question == null ? null : question.get("question") + " " + question.get("option_" + String.valueOf(question.get("correctoption")).toLowerCase());
			long started = System.currentTimeMillis();
			String excerpts = findReferenceExcerpts(tutorialid, sent, usermessage, questiontext);
			log.info("Tutor excerpts: " + excerpts.length() + " chars in " + (System.currentTimeMillis() - started) + " ms");
			tutorMessageContext.putContextValue("referenceexcerpts", viewed + excerpts);
			if (question != null)
			{
				StringBuilder qb = new StringBuilder("Current question: ").append(question.get("question")).append("\n");
				for (String key : new String[] {"option_a", "option_b", "option_c", "option_d", "option_e", "option_f"})
				{
					String v = question.get(key);
					if (v != null && v.length() > 0)
					{
						qb.append(key.substring(7).toUpperCase()).append(": ").append(v).append("\n");
					}
				}
				qb.append("Correct option: ").append(question.get("correctoption")).append("\n");
				if (question.get("rationale") != null)
				{
					qb.append("Rationale: ").append(question.get("rationale")).append("\n");
				}
				// The question's authored source: citable like an excerpt.
				if (question.get("sourcecite") != null)
				{
					sent.put(question.get("sourcecite"), question.get("sourcequote"));
					sent.put(question.get("sourcecite") + "|" + question.get("sourcepage"), question.get("sourcequote"));
					qb.append("Source of the question [")
						.append(question.get("sourcecite"))
						.append(question.get("sourcepage") == null ? "" : ", p. " + question.get("sourcepage"))
						.append("]: ")
						.append(question.get("sourcequote") == null ? "" : question.get("sourcequote"))
						.append("\n");
				}
				prompt = qb + "\n" + prompt;
			}
			String mode = requestValue(tutorMessageContext, request, "mode");
			if (mode != null && mode.length() > 0)
			{
				prompt = "Mode: " + mode + "\n" + prompt;
			}
			String recent = recentConversation(channelid, tutorMessageContext.getUserMessage() == null ? "" : tutorMessageContext.getUserMessage().getId());
			if (recent.length() > 0)
			{
				prompt = recent + "\n" + prompt;
			}
			tutorMessageContext.putContextValue("chathistory", chatHistory);
			tutorMessageContext.putContextValue("learnerprompt", prompt);
			LlmConnection thinking = getMediaArchive().getLlmConnection("thinking");
			started = System.currentTimeMillis();
			LlmResponse response = thinking.callStructure(tutorMessageContext, "chat_tutor_usercomment");
			log.info("Tutor LLM call: " + (System.currentTimeMillis() - started) + " ms");
			JSONObject structured = response.getResponsePayload();
			String message = structured == null ? null : (String) structured.get("message");
			if (message == null)
			{
				tutorMessageContext.error("No answer from tutorial context for: " + usermessage);
				return;
			}
			// The spoken variant rides in the reply's agentcontextvalues, the broadcast's only open
			// field (the app reads it there); no cite guards, brackets stripped as cheap safety.
			// ponytail: no length cap here, speak.json clips at 600 chars on a sentence end already.
			String spoken = voice && structured.get("spoken") != null ? String.valueOf(structured.get("spoken")).replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\s+", " ").trim() : null;
			if (spoken == null || spoken.isEmpty())
			{
				tutorMessageContext.getContext().remove("spoken");
			}
			else
			{
				tutorMessageContext.putContextValue("spoken", spoken);
			}
			// TestU local patch: llamat sometimes brackets a lesson heading as if it were a
			// citation ("[4.3 Autenticación multifactor (MFA)]"); the app reads any
			// [..., p. N] / [..., m:ss] as a source, so drop every bracket group that is not one.
			message = BADCITE.matcher(message).replaceAll("").trim();
			java.util.regex.Matcher cm = ANYCITE.matcher(message);
			StringBuffer grounded = new StringBuffer();
			while (cm.find())
			{
				String key = cm.group(2) == null ? cm.group(1).trim() : cm.group(1).trim() + "|" + cm.group(2);
				if (!sent.containsKey(key))
				{
					log.info("tutor cite dropped: " + cm.group().trim() + " not among sent pages " + sent.keySet());
					cm.appendReplacement(grounded, "");
				}
			}
			cm.appendTail(grounded);
			message = grounded.toString().trim();
			if (!message.contains(">>"))
			{
				// The app renders the ">> ..." lines as follow-up chips; llamat sometimes omits them.
				message = message + "\n\n>> " + ("evaluation".equals(mode) ? "¿Quieres que te explique cómo funciona esta pregunta?"
					: question != null ? "¿Quieres que te explique la pregunta en juego?" : "¿Quieres que te explique algún punto de esta lección?");
			}
			// The RAG path gets the passage and its boxes from the embedding server's
			// sources; here the tutor wrote the citation itself, so look the page up.
			answer = message + quoteForCitation(tutorialid, message, usermessage, sent);
		}

		LlmResponse llmResponse = new BasicLlmResponse();
		llmResponse.setMessage(answer);

		tutorMessageContext.setLastResponse(llmResponse);

		tutorMessageContext.putContextValue("messagerendertype", "agentcomment");

		AutomationStep skillEnabled = tutorMessageContext.getCurrentAutomationStep();
		tutorMessageContext.fireStatusComplete(skillEnabled);
	}

	/**
	 * A context value as this request sent it: from the triggering message's own agentcontextvalues
	 * when it has them (TestU app), else from the message context as before (eMe web chat, no
	 * per-request values).
	 */
	private String requestValue(TutorMessageContext inContext, JSONObject inRequest, String inKey)
	{
		if (inRequest == null)
		{
			return (String) inContext.getContextValue(inKey);
		}
		Object value = inRequest.get(inKey);
		return value == null ? null : value.toString();
	}

	/**
	 * TestU local patch: the citation the app parses — a verbatim passage of the cited page (`> …`, the
	 * sentence sharing most words with the question) and `[Title, p. N]` — from the top RAG source
	 * (`parent_id` = `entityasset_<id>`, `page_label` = pagenum), instead of asking the LLM to write
	 * it. Empty when there is no usable source.
	 */
	/**
	 * Output (the app's splitCite grammar), primary source last:
	 * 
	 * <pre>
	 * > verbatim passage of the primary source
	 *
	 * [Other title, p. 12]          (up to two more distinct sources)
	 * [Primary title, p. 57]        or [Title, m:ss] for a transcribed video
	 * [[hl x,y,w,h;x,y,w,h]]        page-relative boxes of the passage (PDF only)
	 * </pre>
	 * 
	 * The embedding server's sources carry no chunk text (id, parent_id, page_label, file_name, score),
	 * so the passage is picked locally.
	 */
	protected String citeFromSources(JSONArray sources, String query, String answer)
	{
		if (sources == null)
		{
			return "";
		}
		java.util.Set<String> seen = new java.util.HashSet<String>();
		String primary = null;
		String quote = "";
		String rects = "";
		StringBuilder extras = new StringBuilder();
		for (Object o : sources)
		{
			JSONObject source = (JSONObject) o;
			String parent = (String) source.get("parent_id");
			Object page = source.get("page_label");
			if (parent == null || page == null || parent.indexOf('_') < 0)
			{
				continue;
			}
			String docid = parent.substring(parent.indexOf('_') + 1);
			Data doc = getMediaArchive().getCachedData("entityasset", docid);
			if (doc == null || !seen.add(docid + "|" + page))
			{
				continue;
			}
			String where = "p. " + page;
			String text = "";
			Map caption = bestCaption(doc, query, answer);
			if (caption != null)
			{
				where = clock(((Number) caption.get("timecodestart")).longValue());
				text = String.valueOf(caption.get("cliplabel")).trim();
			}
			else
			{
				Data docpage = getMediaArchive().query("entityassetpage").exact("entityasset", docid).exact("pagenum", String.valueOf(page)).searchOne();
				if (docpage != null)
				{
					text = bestSentence(docpage.get("markdowncontent"), query, answer);
				}
			}
			String cite = "[" + doc.getName() + ", " + where + "]";
			if (primary == null)
			{
				primary = cite;
				quote = text;
				if (caption == null && !text.isEmpty())
				{
					rects = highlightRects(doc, String.valueOf(page), text);
				}
			}
			else
			{
				extras.append("\n").append(cite);
			}
			if (seen.size() >= 3)
			{
				break;
			}
		}
		if (primary == null)
		{
			return "";
		}
		return (quote.isEmpty() ? "" : "\n\n> " + quote) + "\n" + extras + "\n" + primary + (rects.isEmpty() ? "" : "\n[[hl " + rects + "]]");
	}

	/**
	 * TestU local patch: the verbatim passage and its page boxes for a citation the tutor wrote itself.
	 * The embedding-server path builds these from its sources (citeFromSources); the local-excerpt path
	 * has no sources, so the cited page is looked up here and the same `> passage` and `[[hl
	 * x,y,w,h;...]]` lines are appended. "" when the citation names no page this tutorial holds, or the
	 * passage is not found on it (video citations carry no boxes). The passage is picked from what the
	 * model read under that heading (inSent, "Title|N" to the excerpt sent) — a page re-scan quoted the
	 * "decálogo" intro of a page cited for its sentence on two-step verification (2026-09-21); the
	 * on-screen page is sent whole, so it scans as before.
	 */
	protected String quoteForCitation(String inTutorialId, String inAnswer, String inQuery, Map<String, String> inSent)
	{
		if (inTutorialId == null)
		{
			return "";
		}
		java.util.regex.Matcher m = PAGECITE.matcher(inAnswer);
		String title = null;
		String page = null;
		while (m.find())
		{
			// Last citation wins: the app reads that one as the primary source.
			title = m.group(1).trim();
			page = m.group(2);
		}
		if (title == null)
		{
			return "";
		}
		String read = inSent.get(title + "|" + page);
		String text = bestSentence(read, inQuery, inAnswer);
		if (text.isEmpty() && read != null && read.length() <= 600)
		{
			// The question's authored source quote: the passage itself, whatever the wording of the reply.
			text = read.trim();
		}
		if (text.isEmpty())
		{
			return "";
		}
		Data doc = null;
		for (Object candidate : getMediaArchive().query("entityasset").exact("entitytutorial", inTutorialId).search())
		{
			if (title.equals(((Data) candidate).getName()))
			{
				doc = (Data) candidate;
				break;
			}
		}
		if (doc == null)
		{
			return "";
		}
		// No boxes, no passage: a citation the learner cannot be shown on the page is
		// a video (whose "page" holds the asset's metadata, not prose) or a sentence
		// pdftotext could not match — quoting either just adds noise.
		String rects = highlightRects(doc, page, text);
		return rects.isEmpty() ? "" : "\n\n> " + text + "\n[[hl " + rects + "]]";
	}

	/** "m:ss" of a millisecond offset — the app's video citation format. */
	protected String clock(long inMs)
	{
		return (inMs / 60000) + ":" + String.format("%02d", (inMs / 1000) % 60);
	}

	/**
	 * The transcript line of a video document best matching the question and answer
	 * (videotrack.captions of its primarymedia); null for PDFs and untranscribed videos, which cite the
	 * page as before.
	 */
	protected Map bestCaption(Data inDoc, String inQuery, String inAnswer)
	{
		String assetid = inDoc.get("primarymedia");
		if (assetid == null)
		{
			return null;
		}
		Data track = getMediaArchive().query("videotrack").exact("assetid", assetid).searchOne();
		Collection captions = track == null ? null : (Collection) track.getValue("captions");
		if (captions == null)
		{
			return null;
		}
		java.util.Set<String> qterms = terms(inQuery);
		java.util.Set<String> agrams = grams(inAnswer);
		Map best = null;
		int bestScore = 0;
		for (Object o : captions)
		{
			Map caption = (Map) o;
			int score = score(String.valueOf(caption.get("cliplabel")), qterms, agrams);
			if (score > bestScore)
			{
				bestScore = score;
				best = caption;
			}
		}
		return best;
	}

	/**
	 * ponytail: the sentence of inText (40–600 chars, markdown marks stripped) scoring highest on 4+
	 * letter words shared with the question (2 each) plus three-word phrases shared with the answer (1
	 * each) — the answer reuses the wording of the chunk the RAG read, so phrase overlap points at the
	 * body sentence rather than a footnote sharing keywords. "" when nothing overlaps. Replace with the
	 * server's matched chunk once /chat returns source text.
	 */
	protected String bestSentence(String inText, String inQuery, String inAnswer)
	{
		if (inText == null)
		{
			return "";
		}
		java.util.Set<String> qterms = terms(inQuery);
		java.util.Set<String> agrams = grams(inAnswer);
		String best = "";
		int bestScore = 0;
		// Page markdown wraps lines mid-sentence: join them, split on sentence ends
		// only — including a period glued to a footnote mark ("encuestadas.1 También") —
		// and on blank lines: two passages of an excerpt are not one sentence of the page.
		for (String raw : inText.replaceAll("\\n\\s*\\n", "\u2029").replaceAll("\\s*\\n\\s*", " ").split("(?<=[.!?])\\s+|(?<=[.!?])(?=\\d{1,2}\\s)|\u2029"))
		{
			String sentence = raw.replaceAll("[*#_`|>]+", "").replaceAll("^\\d{1,2}\\s+", "").replaceAll("\\s+", " ").trim();
			if (sentence.length() < 40 || sentence.length() > 600)
			{
				continue;
			}
			int score = score(sentence, qterms, agrams);
			if (score > bestScore)
			{
				bestScore = score;
				best = sentence;
			}
		}
		return best;
	}

	/** Words of inText, lowercased, accents and punctuation dropped. */
	protected java.util.List<String> tokens(String inText)
	{
		java.util.List<String> tokens = new java.util.ArrayList<String>();
		if (inText != null)
		{
			for (String word : inText.split("\\s+"))
			{
				String n = plain(word);
				if (!n.isEmpty())
				{
					tokens.add(n);
				}
			}
		}
		return tokens;
	}

	/** Tokens of 4+ letters. */
	protected java.util.Set<String> terms(String inText)
	{
		java.util.Set<String> terms = new java.util.HashSet<String>();
		for (String t : tokens(inText))
		{
			if (t.length() >= 4)
			{
				terms.add(t);
			}
		}
		return terms;
	}

	/** Three-word phrases of inText. */
	protected java.util.Set<String> grams(String inText)
	{
		java.util.List<String> t = tokens(inText);
		java.util.Set<String> grams = new java.util.HashSet<String>();
		for (int i = 0; i + 2 < t.size(); i++)
		{
			grams.add(t.get(i) + " " + t.get(i + 1) + " " + t.get(i + 2));
		}
		return grams;
	}

	protected int score(String inSentence, java.util.Set<String> inQueryTerms, java.util.Set<String> inAnswerGrams)
	{
		java.util.List<String> t = tokens(inSentence);
		int score = 0;
		for (String w : new java.util.HashSet<String>(t))
		{
			if (inQueryTerms.contains(w))
			{
				score += 2;
			}
		}
		for (int i = 0; i + 2 < t.size(); i++)
		{
			if (inAnswerGrams.contains(t.get(i) + " " + t.get(i + 1) + " " + t.get(i + 2)))
			{
				score++;
			}
		}
		return score;
	}

	// ponytail: poppler on the PATH (or Homebrew's); an EME commandmap entry when it moves servers.
	private static final String[] PDFTOTEXT = {"/opt/homebrew/bin/pdftotext", "/usr/bin/pdftotext", "/usr/local/bin/pdftotext"};

	/** `[Title, p. N]` as the tutor writes it; video citations (`[Title, m:ss]`) carry no boxes. */
	private static final java.util.regex.Pattern PAGECITE = java.util.regex.Pattern.compile("\\[([^\\[\\]]+?),\\s*p\\.?\\s*(\\d+)\\]");

	/**
	 * Any citation, page or video: group 1 the title, group 2 the page (null for `m:ss`). Leading
	 * blanks included, like BADCITE.
	 */
	private static final java.util.regex.Pattern ANYCITE = java.util.regex.Pattern.compile("[ \\t]*\\[([^\\[\\]\\n]+?),\\s*(?:p\\.?\\s*(\\d+)|\\d+:\\d\\d)\\]");

	/** In a past reply: a `> quote` or `[[hl …]]` line (not a `>>` follow-up), or any citation. */
	private static final java.util.regex.Pattern STALECITE = java.util.regex.Pattern.compile("(?m)^(?:> |\\[\\[hl ).*$\\n?|[ \\t]*\\[[^\\[\\]\\n]+?,\\s*(?:p\\.?\\s*\\d+|\\d+:\\d\\d)\\]");

	/**
	 * A single bracket group that is not a `[Title, p. N]` / `[Title, m:ss]` citation (never a `[[hl`
	 * box).
	 */
	private static final java.util.regex.Pattern BADCITE = java.util.regex.Pattern.compile("[ \\t]*(?<!\\[)\\[(?!\\[)(?![^\\]\\n]+,\\s*(p\\.\\s*\\d+|\\d+:\\d\\d)\\])[^\\[\\]\\n]*\\](?!\\])");

	/**
	 * Page-relative boxes (x,y,w,h in 0–1, one per text line, ';'-joined) of inQuote on page inPage of
	 * inDoc's PDF, from `pdftotext -bbox-layout`; "" when the quote is not found there (≥60 % of its
	 * words in order) or pdftotext is unavailable. The app paints them over the rendered page.
	 */
	protected String highlightRects(Data inDoc, String inPage, String inQuote)
	{
		try
		{
			Asset asset = getMediaArchive().getAsset(inDoc.get("primarymedia"));
			if (asset == null)
			{
				return "";
			}
			String path = getMediaArchive().getOriginalContent(asset).getAbsolutePath();
			if (path == null || !path.toLowerCase().endsWith(".pdf"))
			{
				return "";
			}
			String exe = "pdftotext";
			for (String candidate : PDFTOTEXT)
			{
				if (new java.io.File(candidate).canExecute())
				{
					exe = candidate;
					break;
				}
			}
			Process p = new ProcessBuilder(exe, "-bbox-layout", "-f", inPage, "-l", inPage, path, "-").redirectErrorStream(true).start();
			String xml = new String(p.getInputStream().readAllBytes(), "UTF-8");
			p.waitFor();
			java.util.regex.Matcher pm = java.util.regex.Pattern.compile("<page width=\"([\\d.]+)\" height=\"([\\d.]+)\"").matcher(xml);
			if (!pm.find())
			{
				return "";
			}
			double pw = Double.parseDouble(pm.group(1));
			double ph = Double.parseDouble(pm.group(2));
			java.util.List<String> words = new java.util.ArrayList<String>();
			java.util.List<double[]> boxes = new java.util.ArrayList<double[]>();
			java.util.regex.Matcher wm = java.util.regex.Pattern.compile("<word xMin=\"([\\d.]+)\" yMin=\"([\\d.]+)\" xMax=\"([\\d.]+)\" yMax=\"([\\d.]+)\">([^<]*)</word>").matcher(xml);
			while (wm.find())
			{
				words.add(plain(wm.group(5)));
				boxes.add(new double[] {Double.parseDouble(wm.group(1)), Double.parseDouble(wm.group(2)), Double.parseDouble(wm.group(3)), Double.parseDouble(wm.group(4))});
			}
			java.util.List<String> quote = new java.util.ArrayList<String>();
			for (String w : inQuote.split("\\s+"))
			{
				String n = plain(w);
				if (!n.isEmpty())
				{
					quote.add(n);
				}
			}
			if (quote.isEmpty() || words.isEmpty())
			{
				return "";
			}
			int bestStart = -1;
			int bestHits = 0;
			for (int i = 0; i < words.size(); i++)
			{
				int hits = 0;
				for (int j = 0; j < quote.size() && i + j < words.size(); j++)
				{
					if (words.get(i + j).equals(quote.get(j)))
					{
						hits++;
					}
				}
				if (hits > bestHits)
				{
					bestHits = hits;
					bestStart = i;
				}
			}
			if (bestStart < 0 || bestHits < quote.size() * 0.6)
			{
				return "";
			}
			StringBuilder out = new StringBuilder();
			double[] line = null;
			int end = Math.min(words.size(), bestStart + quote.size());
			for (int i = bestStart; i < end; i++)
			{
				double[] b = boxes.get(i);
				if (line != null && Math.abs(b[1] - line[1]) < 2)
				{
					line[0] = Math.min(line[0], b[0]);
					line[2] = Math.max(line[2], b[2]);
					line[3] = Math.max(line[3], b[3]);
					continue;
				}
				appendRect(out, line, pw, ph);
				line = b.clone();
			}
			appendRect(out, line, pw, ph);
			return out.toString();
		}
		catch (Exception e)
		{
			log.info("No highlight for " + inDoc.getName() + " p. " + inPage + ": " + e);
			return "";
		}
	}

	private void appendRect(StringBuilder inOut, double[] inBox, double inW, double inH)
	{
		if (inBox == null)
		{
			return;
		}
		if (inOut.length() > 0)
		{
			inOut.append(';');
		}
		inOut.append(String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", inBox[0] / inW, inBox[1] / inH, (inBox[2] - inBox[0]) / inW, (inBox[3] - inBox[1]) / inH));
	}

	/**
	 * ponytail: plural fold only ("proveedores" = "proveedor"), what the index's English snowball does
	 * to Spanish, so a passage matches the words the search engine matched the page on. Upgrade path:
	 * the searcher's own highlighting (highlight="true" on the field, SearchHitData.getHighlights) once
	 * description is analysed in Spanish; its 180-char fragments are not passages to read.
	 */
	private String fold(String inToken)
	{
		return inToken.length() > 4 ? inToken.replaceAll("(es|s)$", "") : inToken;
	}

	/** Lowercase, accents and punctuation dropped — pdftotext words vs markdown words. */
	private String plain(String inWord)
	{
		return java.text.Normalizer.normalize(inWord, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^\\p{L}\\p{N}]", "");
	}

	/**
	 * TestU local patch: keyword-matched pages of the reference documents (entityasset records linked
	 * to the tutorial, split into entityassetpage records with markdowncontent) as prompt text, each
	 * headed by the document title and page so the tutor can cite it. Empty when nothing matches.
	 */
	/**
	 * One page of a document as a citable excerpt (its "Title|N" added to inSent), or "" when either id
	 * is missing or the page is unknown.
	 */
	protected String viewedPage(String inAssetId, String inPage, Map<String, String> inSent)
	{
		if (inAssetId == null || inAssetId.isEmpty() || inPage == null || inPage.isEmpty())
		{
			return "";
		}
		Data page = getMediaArchive().query("entityassetpage").exact("entityasset", inAssetId).exact("pagenum", inPage).searchOne();
		String text = page == null ? null : page.get("markdowncontent");
		if (text == null || text.isEmpty())
		{
			return "";
		}
		Data doc = getMediaArchive().getCachedData("entityasset", inAssetId);
		String title = doc == null ? "Reference document" : doc.getName();
		text = text.length() > 4000 ? text.substring(0, 4000) : text;
		inSent.put(title, text);
		inSent.put(title + "|" + inPage, text);
		return "[" + title + ", p. " + inPage + "] (the page the learner is looking at)\n" + text + "\n\n";
	}

	/**
	 * Up to 3 pages of the tutorial's documents most relevant to the queries (the learner's words
	 * first, then the question's), each recorded in inSent as "Title|N" with the text sent. "" when
	 * nothing matches. A page longer than the budget is sent as its passages sharing most words with
	 * the queries; on a video's page each caption cue is a paragraph, its timestamp is what a `m:ss`
	 * citation needs.
	 */
	protected String findReferenceExcerpts(String tutorialid, Map<String, String> inSent, String... queries)
	{
		if (tutorialid == null)
		{
			return "";
		}
		Collection<MultiValued> docs = getMediaArchive().query("entityasset").exact("entitytutorial", tutorialid).search();
		if (docs.isEmpty())
		{
			return "";
		}
		// ponytail: ranked keyword match on the page text; the embedding server does the
		// real semantic retrieval when its /chat works. markdowncontent is not_analyzed (one
		// keyword per page): search description, which holds the page text. match = one analyzed
		// OR query ranked by relevance (lowersnowball: accents folded, English snowball strips the
		// plural -s/-es; the index's "spanish" analyzer would need BaseElasticSearcher.configureDetail
		// and a reindex, description's analyzer is not a field attribute). freeform split words on ASCII
		// letters ("Política" -> "Pol" "tica") and made its last word mandatory even in "a OR b"
		// form, so an applied question ending in a word no page had ("...por el agua?") found
		// nothing and the tutor refused (2026-09-16). Each query separately, so the question's
		// words cannot drown the learner's: the best 2 pages of every query but the last, which
		// fills up to 3.
		Map<String, Data> pages = new LinkedHashMap<String, Data>();
		for (int i = 0; i < queries.length && pages.size() < 3; i++)
		{
			StringBuilder terms = new StringBuilder();
			// Words of 4+ letters only, punctuation stripped: "?" and "*" are wildcards to the
			// search engine, so "¿Qué son los derechos humanos?" matched nothing (2026-09-04).
			for (String word : queries[i] == null ? new String[0] : queries[i].split("[^\\p{L}\\p{N}]+"))
			{
				if (word.length() >= 4)
				{
					terms.append(' ').append(word);
				}
			}
			if (terms.length() == 0)
			{
				continue;
			}
			int limit = i < queries.length - 1 ? pages.size() + 2 : 3;
			for (Object hit : getMediaArchive().query("entityassetpage").orgroup("entityasset", docs).match("description", terms.toString().trim()).hitsPerPage(3).search().getPageOfHits())
			{
				Data page = (Data) hit;
				if (pages.size() < limit)
				{
					pages.putIfAbsent(page.getId(), page);
				}
			}
		}
		StringBuilder out = new StringBuilder();
		// The learner's words weigh 3, the question's 1: the question in play is there to find a
		// page when the message has no keywords, not to pick the passage when it has.
		Map<String, Double> qterms = new HashMap<String, Double>();
		for (int i = 0; i < queries.length; i++)
		{
			for (String term : terms(queries[i]))
			{
				qterms.putIfAbsent(fold(term), i == 0 ? 3.0 : 1.0);
			}
		}
		// Calibration knob: catalogsettings "tutorexcerptchars", chars sent per retrieved page.
		// 4000 sends a typical page (~2800) whole and the best 4000 of a longer one (a video's
		// transcript), not its first 4000. Measured on llamat 2026-09-21, 21 questions x 3:
		// 4000 = 5374 prompt tokens, 11 not-found; 2500 = 4563-4745, 15-16; 1500 = 4206, 16-20;
		// LLM time 3.3 s vs 3.1 s. Trimmed pages lose the lines around a hit that let the
		// model settle a borderline question, for a quarter second.
		String budget = getMediaArchive().getCatalogSettingValue("tutorexcerptchars");
		int max = budget == null || budget.isEmpty() ? 4000 : Integer.parseInt(budget);
		for (Data page : pages.values())
		{
			String text = page.get("markdowncontent");
			Data doc = getMediaArchive().getCachedData("entityasset", page.get("entityasset"));
			String title = doc == null ? "Reference document" : doc.getName();
			if (text == null || text.isEmpty() || inSent.containsKey(title + "|" + page.get("pagenum")))
			{
				continue; // the page on screen, already sent whole
			}
			text = bestParagraphs(text, qterms, max);
			inSent.put(title, text);
			inSent.put(title + "|" + page.get("pagenum"), text);
			out.append("[").append(title).append(", p. ").append(page.get("pagenum")).append("]\n");
			out.append(text).append("\n\n");
		}
		return out.toString();
	}

	/**
	 * The passages of inText (~300+ chars: blank-line blocks, their sentences, or the SRT cues of a
	 * transcript, short ones merged forward) scoring highest on inTerms (folded word to weight), in
	 * page order, up to inMax chars; the first passages when none scores (the search engine matched a
	 * stemmed form). The ~4000-char page cost ~1000 prompt tokens each, ~2 s of llamat prompt
	 * processing per 4K, and buried the passage the tutor should quote.
	 */
	protected String bestParagraphs(String inText, Map<String, Double> inTerms, int inMax)
	{
		if (inText.length() <= inMax)
		{
			return inText;
		}
		// Short blocks (a heading, a 4-second cue) ride with what follows them up to ~300 chars:
		// alone, a title line outscored its body and a cue was half a sentence.
		java.util.List<String> chunks = new java.util.ArrayList<String>();
		StringBuilder chunk = new StringBuilder();
		for (String block : inText.trim().split("\\n\\s*\\n|\\n(?=\\d+\\n\\d\\d:\\d\\d)"))
		{
			// A dense PDF page is one block with no blank line: its sentences are the units
			// (never inside a caption cue, which must keep its timestamp).
			for (String piece : block.contains("-->") ? new String[] {block} : block.split("(?<=[.!?])\\s+"))
			{
				chunk.append(chunk.length() > 0 ? "\n" : "").append(piece);
				if (chunk.length() >= 300)
				{
					chunks.add(chunk.toString());
					chunk.setLength(0);
				}
			}
		}
		if (chunk.length() > 0)
		{
			chunks.add(chunk.toString());
		}
		String[] paragraphs = chunks.toArray(new String[chunks.size()]);
		// Each occurrence of a query word counts 1 / (passages of this page holding it): "derechos"
		// and "plan" are in every passage of the PNA and outscored the one on "debida diligencia".
		Map<String, Integer> df = new HashMap<String, Integer>();
		java.util.List<java.util.List<String>> words = new java.util.ArrayList<java.util.List<String>>();
		for (String p : paragraphs)
		{
			java.util.List<String> folded = new java.util.ArrayList<String>();
			for (String t : tokens(p))
			{
				folded.add(fold(t));
			}
			words.add(folded);
			for (String t : new java.util.HashSet<String>(folded))
			{
				df.merge(t, 1, Integer::sum);
			}
		}
		double[] scores = new double[paragraphs.length];
		Integer[] order = new Integer[paragraphs.length];
		for (int i = 0; i < paragraphs.length; i++)
		{
			for (String t : words.get(i))
			{
				scores[i] += inTerms.containsKey(t) ? inTerms.get(t) / df.get(t) : 0;
			}
			order[i] = i;
		}
		java.util.Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));
		// Each hit rides with the passage after and before it: alone, the sentence naming "PR-CER"
		// lost the lines saying what CER is, and a caption cue lost the rest of its sentence.
		java.util.Set<Integer> picks = new java.util.LinkedHashSet<Integer>();
		for (int i : order)
		{
			if (scores[i] == 0 && scores[order[0]] > 0)
			{
				break;
			}
			for (int k : new int[] {i, i + 1, i - 1})
			{
				if (k >= 0 && k < paragraphs.length)
				{
					picks.add(k);
				}
			}
		}
		String[] keep = new String[paragraphs.length];
		int length = 0;
		for (int i : picks)
		{
			if (inMax - length < 200)
			{
				break;
			}
			// A passage past the budget is cut (at a sentence end when it has one), not skipped:
			// it outscores what follows.
			keep[i] = paragraphs[i];
			if (keep[i].length() > inMax - length)
			{
				keep[i] = keep[i].substring(0, inMax - length);
				int end = Math.max(keep[i].lastIndexOf(". "), keep[i].lastIndexOf(".\n"));
				keep[i] = end > keep[i].length() / 2 ? keep[i].substring(0, end + 1) : keep[i];
			}
			length += keep[i].length();
		}
		StringBuilder out = new StringBuilder();
		for (String p : keep)
		{
			if (p != null)
			{
				out.append(out.length() > 0 ? "\n\n" : "").append(p);
			}
		}
		return out.toString();
	}

	/**
	 * TestU local patch: what `tutoranswer` knows about this learner — their past attempts on this
	 * question (most recent first), their tally in this tutorial broken down by section (topic /
	 * subtopic), and their overall tally — as prompt text. Empty when there is nothing recorded.
	 */
	protected String answerHistory(String userId, String questionid, String tutorialid, String sectionid)
	{
		StringBuilder out = new StringBuilder();
		if (questionid != null && questionid.length() > 0)
		{
			Collection<MultiValued> past = getMediaArchive().query("tutoranswer").exact("user", userId).exact("entityquestion", questionid).sort("datecreatedDown").search();
			if (!past.isEmpty())
			{
				int correct = 0;
				StringBuilder items = new StringBuilder();
				for (MultiValued a : past)
				{
					boolean ok = isCorrect(a);
					if (ok)
					{
						correct++;
					}
					if (items.length() > 0)
					{
						items.append("; ");
					}
					items.append(a.get("selectedoption")).append(" (").append(a.get("answerconfidence")).append(", ").append(ok ? "correct" : "incorrect").append(")");
				}
				out.append("Past answers of the learner on this question, most recent first: ")
					.append(items)
					.append(". Total ")
					.append(past.size())
					.append(" attempts, ")
					.append(correct)
					.append(" correct.\n");
			}
		}
		// Mastery as the server computed it (tutormastery, LearningEngine.recomputeMastery, every 15 min)
		// instead of re-tallying every answer of the learner on each message.
		// ponytail: up to 15 min stale; the question's own attempts above are live.
		try
		{
			Data section = sectionid == null ? null : getMediaArchive().getCachedData("componentsection", sectionid);
			appendMastery(out, "current subtopic" + (section == null ? "" : " '" + section.getName() + "'"), getMediaArchive().getData("tutormastery", userId + "_" + sectionid));
			Data tutorial = tutorialid == null ? null : getMediaArchive().getCachedData("entitytutorial", tutorialid);
			String topicid = tutorial == null ? null : tutorial.get("entitytopic");
			Data topic = topicid == null ? null : getMediaArchive().getCachedData("entitytopic", topicid);
			if (topicid != null)
			{
				appendMastery(out, "topic" + (topic == null ? "" : " '" + topic.getName() + "'"), getMediaArchive().getData("tutormastery", userId + "_topic_" + topicid));
			}
		}
		catch (Exception e)
		{
			log.info("No tutormastery for " + userId + ": " + e);
		}
		return out.toString();
	}

	private void appendMastery(StringBuilder inOut, String inScope, Data inRow)
	{
		if (inRow == null)
		{
			return;
		}
		inOut.append("Mastery of the learner in the ")
			.append(inScope)
			.append(": ")
			.append(inRow.get("masterypercent"))
			.append("% (band ")
			.append(inRow.get("band"))
			.append("), ")
			.append(inRow.get("answered"))
			.append(" of ")
			.append(inRow.get("questions"))
			.append(" questions answered, ")
			.append(inRow.get("certainwrong"))
			.append(" wrong answers given with confidence, ")
			.append(inRow.get("unsurecorrect"))
			.append(" right answers given unsure.\n");
	}

	/**
	 * The last few turns of this channel, oldest first: what the learner asked or answered and what the
	 * tutor replied, so a follow-up like "¿y por qué?" has its antecedent. Each turn cut to 400 chars.
	 */
	protected String recentConversation(String inChannelId, String inCurrentMessageId)
	{
		Collection<Data> rows = getMediaArchive().query("chatterbox")
			.exact("channel", inChannelId)
			.orgroup("functionname", "chat_tutor_usercomment chat_tutor_answer")
			.sort("dateDown")
			.hitsPerPage(7)
			.search()
			.getPageOfHits();
		java.util.LinkedList<String> turns = new java.util.LinkedList<String>();
		JSONParser parser = new JSONParser();
		for (Data row : rows)
		{
			if (row.getId().equals(inCurrentMessageId))
			{
				continue;
			}
			String text;
			if ("agent".equals(row.get("user")))
			{
				// Citations, `> quote` and `[[hl` lines of past replies stripped: those pages are
				// not sent with this message, and a copied stale citation is dropped anyway.
				text = "Tutor: " + STALECITE.matcher(String.valueOf(row.get("message"))).replaceAll("").trim();
			}
			else
			{
				JSONObject values = null;
				try
				{
					values = (JSONObject) parser.parse(String.valueOf(row.get("agentcontextvalues")));
				}
				catch (Exception e)
				{
					continue;
				}
				if (values == null)
				{
					continue; // rows saved without agentcontextvalues parse "null" to null
				}
				if (values.get("query") != null)
				{
					text = "Learner: " + values.get("query");
				}
				else if (values.get("selectedoption") != null)
				{
					text = "Learner answered " + values.get("selectedoption") + " (" + values.get("confidence") + ")";
				}
				else
				{
					continue;
				}
			}
			// A past not-found reply made the model repeat it even with excerpts in hand.
			if (text.endsWith("null") || text.endsWith("No content available") || text.startsWith("Tutor: No lo encuentro en las fuentes"))
			{
				continue;
			}
			turns.addFirst(text.length() > 400 ? text.substring(0, 400) + "…" : text);
		}
		return turns.isEmpty() ? "" : "Recent conversation, oldest first:\n" + String.join("\n", turns) + "\n";
	}

	private boolean isCorrect(MultiValued answer)
	{
		return Boolean.parseBoolean(String.valueOf(answer.get("iscorrect")));
	}
}
