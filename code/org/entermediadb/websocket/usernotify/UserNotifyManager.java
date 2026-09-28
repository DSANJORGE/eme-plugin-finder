package org.entermediadb.websocket.usernotify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.openedit.ModuleManager;
import org.openedit.data.SearcherManager;
import org.openedit.users.User;
import org.openedit.util.StringEncryption;

public class UserNotifyManager
{
	private static final Log log = LogFactory.getLog(UserNotifyManager.class);

	protected StringEncryption fieldStringEncrytion;
	protected SearcherManager fieldSearcherManager;
	protected String fieldCurrentConnectionId;
	protected ModuleManager fieldModuleManager;
	protected Map<String, List> fieldUserAuthenticatedConnections;

	public SearcherManager getSearcherManager()
	{
		return fieldSearcherManager;
	}

	public void setSearcherManager(SearcherManager inSearcherManager)
	{
		fieldSearcherManager = inSearcherManager;
	}

	public ModuleManager getModuleManager()
	{
		return fieldModuleManager;
	}

	public void setModuleManager(ModuleManager inModuleManager)
	{
		fieldModuleManager = inModuleManager;
	}

	public Map<String, List> getUserAuthenticatedConnections()
	{
		if (fieldUserAuthenticatedConnections == null)
		{
			// Written by websocket threads (login/close) and read by request threads (sentNotifications)
			fieldUserAuthenticatedConnections = new java.util.concurrent.ConcurrentHashMap(); /// TODO: Remove unused ones on a loop
		}
		return fieldUserAuthenticatedConnections;
	}

	protected void receiveLogin(UserNotifyConnection inConnection, JSONObject map)
	{
		String username = (String) map.get("userid");
		String keyorpasswordentered = (String) map.get("entermediakey");
		if (username == null || keyorpasswordentered == null)
		{
			return;
		}
		// setDesktop(getDesktop());
		// authenticated
		User user = (User) getSearcherManager().getData("system", "user", username);
		if (user == null) // TODO: Authenticate key (with expiration)
		{
			JSONObject authenticated = new JSONObject();
			authenticated.put("command", "authenticatefail");
			authenticated.put("reason", "User did not exist");
			inConnection.sendMessage(authenticated);
			return;
		}
		String key = getStringEncrytion().getEnterMediaKey(user);

		// Apps hold the OAuth access_token (a temp key with a timestamp); verify that one with its expiry
		boolean tempkey = keyorpasswordentered.contains(StringEncryption.TIMESTAMP);
		if (tempkey && !getStringEncrytion().verifyEnterMediaKey(user, user.getPassword(), keyorpasswordentered))
		{
			JSONObject authenticated = new JSONObject();
			authenticated.put("command", "authenticatefail");
			authenticated.put("reason", "Key did not match");
			inConnection.sendMessage(authenticated);
			return;
		}
		if (!tempkey && !key.equals(keyorpasswordentered))
		{
			// check password
			String clearpassword = getStringEncrytion().decryptIfNeeded(user.getPassword());
			if (!keyorpasswordentered.equals(clearpassword))
			{
				JSONObject authenticated = new JSONObject();
				authenticated.put("command", "authenticatefail");
				authenticated.put("reason", "Password did not match");
				inConnection.sendMessage(authenticated);
				return;
			}
		}
		inConnection.setUserId(username);

		List connections = getUserAuthenticatedConnections().computeIfAbsent(username, k -> new ArrayList());
		synchronized (connections)
		{
			connections.add(inConnection);
		}

		String connectionid = (String) map.get("connectionid");
		inConnection.setCurrentConnectionId(connectionid);

		JSONObject authenticated = new JSONObject();
		authenticated.put("command", "authenticated");
		authenticated.put("entermedia.key", key);
		inConnection.sendMessage(authenticated);
	}

	public StringEncryption getStringEncrytion()
	{
		if (fieldStringEncrytion == null)
		{
			fieldStringEncrytion = (StringEncryption) getModuleManager().getBean("stringEncryption");
		}
		return fieldStringEncrytion;
	}

	public void setStringEncrytion(StringEncryption inStringEncrytion)
	{
		fieldStringEncrytion = inStringEncrytion;
	}

	public void onMessage(UserNotifyConnection inConnection, JSONObject map)
	{
		try
		{
			// message = message.replaceAll("null", "\"null\"");
			String command = (String) map.get("command");
			// getDesktop().setLastCommand(command);
			if ("login".equals(command)) // Return all the annotation on this asset
			{
				receiveLogin(inConnection, map);
			}
			/*
			 * else if ("folderedited".equals(command)) //Return all the annotation on this asset { String
			 * foldername = (String)map.get("foldername"); getDesktop().addEditedCollection(foldername); } else
			 * if ("busychanged".equals(command)) //Return all the annotation on this asset { boolean busy =
			 * (boolean)map.get("isbusy"); getDesktop().setBusy(busy); } else if
			 * ("folderedited".equals(command)) //Return all the annotation on this asset { String foldername =
			 * (String)map.get("foldername"); getDesktop().addEditedCollection(foldername); }
			 */

		}
		catch (Exception e)
		{
			// TODO Auto-generated catch block
			log.error(e);
			e.printStackTrace();
		}

	}

	public void removeConnection(UserNotifyConnection inUserNotifyConnection)
	{
		if (inUserNotifyConnection.getUserId() == null)
		{
			return; // closed before login
		}
		Collection connections = getUserAuthenticatedConnections().get(inUserNotifyConnection.getUserId());
		if (connections != null)
		{
			synchronized (connections)
			{
				connections.remove(inUserNotifyConnection);
			}
		}

	}

	public void sentNotifications(String inUserId, JSONObject inMessage)
	{
		List connections = inUserId == null ? null : getUserAuthenticatedConnections().get(inUserId);
		if (connections != null)
		{
			synchronized (connections)
			{
				Collection toremove = new ArrayList(connections.size());
				for (Iterator iterator = connections.iterator(); iterator.hasNext();)
				{
					UserNotifyConnection connection = (UserNotifyConnection) iterator.next();
					if (!connection.sendMessage(inMessage))
					{
						toremove.add(connection);
					}
				}
				connections.removeAll(toremove);
			}
		}
		else
		{
			log.debug("No authenticated connections to notify " + inUserId);
		}
	}

}
