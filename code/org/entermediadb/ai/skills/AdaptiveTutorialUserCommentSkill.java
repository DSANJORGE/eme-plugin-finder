package org.entermediadb.ai.skills;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
			String viewed = viewedPage(requestValue(tutorMessageContext, request, "entityasset"), requestValue(tutorMessageContext, request, "pagenum"));
			// A session follow-up ("¿Por qué las otras opciones están mal?") has no keywords of its own: the
			// question's text and its correct option find the pages the learner's words cannot.
			String questiontext = question == null ? null : question.get("question") + " " + question.get("option_" + String.valueOf(question.get("correctoption")).toLowerCase());
			tutorMessageContext.putContextValue("referenceexcerpts", viewed + findReferenceExcerpts(tutorialid, usermessage, questiontext));
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
					qb.append("Source of the question [").append(question.get("sourcecite")).append(question.get("sourcepage") == null ? "" : ", p. " + question.get("sourcepage")).append("]: ").append(question.get("sourcequote") == null ? "" : question.get("sourcequote")).append("\n");
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
			LlmResponse response = thinking.callStructure(tutorMessageContext, "chat_tutor_usercomment");
			JSONObject structured = response.getResponsePayload();
			String message = structured == null ? null : (String) structured.get("message");
			if (message == null)
			{
				tutorMessageContext.error("No answer from tutorial context for: " + usermessage);
				return;
			}
			answer = message;
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
		// only — including a period glued to a footnote mark ("encuestadas.1 También").
		for (String raw : inText.replaceAll("\\s*\\n\\s*", " ").split("(?<=[.!?])\\s+|(?<=[.!?])(?=\\d{1,2}\\s)"))
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
	/** One page of a document as a citable excerpt, or "" when either id is missing or the page is unknown. */
	protected String viewedPage(String inAssetId, String inPage)
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
		return "[" + (doc == null ? "Reference document" : doc.getName()) + ", p. " + inPage + "] (the page the learner is looking at)\n" + (text.length() > 4000 ? text.substring(0, 4000) : text) + "\n\n";
	}

	/**
	 * Pages of the tutorial's documents matching the queries, tried in order: every word of a query first
	 * (the learner's words, then the question's), any word of a query last. "" when nothing matches.
	 */
	protected String findReferenceExcerpts(String tutorialid, String... queries)
	{
		if (tutorialid == null)
		{
			return "";
		}
		// ponytail: plain keyword match on the page text; the embedding server does the
		// real semantic retrieval when its /chat works. Words of 4+ letters only,
		// punctuation stripped: "?" and "*" are wildcards to the search engine, so
		// "¿Qué son los derechos humanos?" matched nothing (2026-09-04).
		List<String> termsets = new ArrayList<String>();
		for (String query : queries)
		{
			StringBuilder terms = new StringBuilder();
			for (String word : query == null ? new String[0] : query.split("[^\\p{L}\\p{N}]+"))
			{
				if (word.length() >= 4)
				{
					terms.append(terms.length() > 0 ? " " : "").append(word);
				}
			}
			if (terms.length() > 0)
			{
				termsets.add(terms.toString());
			}
		}
		if (termsets.isEmpty())
		{
			return "";
		}
		Collection<MultiValued> docs = getMediaArchive().query("entityasset").exact("entitytutorial", tutorialid).search();
		if (docs.isEmpty())
		{
			return "";
		}
		// markdowncontent is not_analyzed (one keyword per page), so a word search there never
		// matched and every reply had no excerpts. description is analyzed and holds the page
		// text. All words first; any word when no page has them all.
		// ponytail: wildcard OR is unranked; the embed server's semantic /chat replaces this.
		Collection<Data> pages = Collections.emptyList();
		for (String terms : termsets)
		{
			pages = getMediaArchive().query("entityassetpage").orgroup("entityasset", docs).freeform("description", terms).hitsPerPage(3).search().getPageOfHits();
			if (!pages.isEmpty())
			{
				break;
			}
		}
		for (String terms : termsets)
		{
			if (!pages.isEmpty())
			{
				break;
			}
			pages = getMediaArchive().query("entityassetpage").orgroup("entityasset", docs).freeform("description", terms.replace(" ", " OR ")).hitsPerPage(3).search().getPageOfHits();
		}
		StringBuilder out = new StringBuilder();
		for (Data page : pages)
		{
			String text = page.get("markdowncontent");
			if (text == null || text.isEmpty())
			{
				continue;
			}
			Data doc = getMediaArchive().getCachedData("entityasset", page.get("entityasset"));
			out.append("[").append(doc == null ? "Reference document" : doc.getName()).append(", p. ").append(page.get("pagenum")).append("]\n");
			// 2500 cut the second half of a typical page (~2800 chars).
			out.append(text.length() > 4000 ? text.substring(0, 4000) : text).append("\n\n");
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
		inOut.append("Mastery of the learner in the ").append(inScope).append(": ").append(inRow.get("masterypercent")).append("% (band ").append(inRow.get("band")).append("), ")
			.append(inRow.get("answered")).append(" of ").append(inRow.get("questions")).append(" questions answered, ")
			.append(inRow.get("certainwrong")).append(" wrong answers given with confidence, ")
			.append(inRow.get("unsurecorrect")).append(" right answers given unsure.\n");
	}

	/**
	 * The last few turns of this channel, oldest first: what the learner asked or answered and what the tutor
	 * replied, so a follow-up like "¿y por qué?" has its antecedent. Each turn cut to 400 chars.
	 */
	protected String recentConversation(String inChannelId, String inCurrentMessageId)
	{
		Collection<Data> rows = getMediaArchive().query("chatterbox").exact("channel", inChannelId).orgroup("functionname", "chat_tutor_usercomment chat_tutor_answer").sort("dateDown").hitsPerPage(7).search().getPageOfHits();
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
				text = "Tutor: " + row.get("message");
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
