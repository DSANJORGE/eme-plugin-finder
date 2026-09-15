package org.entermediadb.ai.llm.router;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.entermediadb.asset.MediaArchive;
import org.openedit.Data;
import org.openedit.hittracker.HitTracker;

public class AiCallLogCleaner
{
	public static int deleteOlderThanDays(MediaArchive inArchive, int inDays)
	{
		Calendar cutoff = Calendar.getInstance();
		cutoff.add(Calendar.DAY_OF_YEAR, -inDays);
		HitTracker hits = inArchive.query("aicalllog").before("datecreated", cutoff.getTime()).search();
		List<Data> rows = new ArrayList<Data>();
		for (Object hit : hits)
		{
			rows.add((Data) hit);
		}
		if (!rows.isEmpty())
		{
			inArchive.getSearcher("aicalllog").deleteAll(rows, null);
		}
		return rows.size();
	}
}
