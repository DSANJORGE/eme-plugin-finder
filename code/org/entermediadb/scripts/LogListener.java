package org.entermediadb.scripts;

public interface LogListener
{
	void handleLog(String inType, String inText, Throwable inEx);

	void flush();
}
