/*
 * Created on Oct 19, 2004
 */
package org.entermediadb.elasticsearch.searchers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.openedit.Data;
import org.openedit.OpenEditException;
import org.openedit.data.PropertyDetails;
import org.openedit.hittracker.HitTracker;
import org.openedit.hittracker.SearchQuery;
import org.openedit.profile.UserProfile;
import org.openedit.users.BaseUser;
import org.openedit.users.Group;
import org.openedit.users.GroupSearcher;
import org.openedit.users.User;
import org.openedit.users.UserSearcher;
import org.openedit.users.filesystem.XmlUserArchive;
import org.openedit.util.StringEncryption;

/**
 *
 */
public class ElasticUserSearcher extends ElasticListSearcher implements UserSearcher
{
	private static final Log log = LogFactory.getLog(ElasticUserSearcher.class);
	protected XmlUserArchive fieldXmlUserArchive;
	private User NULLUSER = new BaseUser();

	@Override
	public Data createNewData()
	{
		BaseUser user = (BaseUser) super.createNewData();
		user.setGroupSearcher(getGroupSearcher());
		user.setEnabled(true);
		return user;
	}

	public XmlUserArchive getXmlUserArchive()
	{
		if (fieldXmlUserArchive == null)
		{
			fieldXmlUserArchive = (XmlUserArchive) getModuleManager().getBean(getCatalogId(), "xmlUserArchive");

		}

		return fieldXmlUserArchive;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.users.UserSearcherI#getUser(java.lang.String)
	 */
	public User getUser(String inAccount)
	{
		return getUser(inAccount, false);
	}

	@Override
	public User getUser(String inAccount, boolean inCached)
	{
		if (inAccount == null)
		{
			return null;
		}
		if (inCached)
		{
			User user = (User) getCacheManager().get("usercache", inAccount);
			if (user == null)
			{
				user = (User) searchById(inAccount);
				if (user == null)
				{
					user = NULLUSER;
				}
				getCacheManager().put("usercache", inAccount, user);
			}
			if (user == NULLUSER)
			{
				return null;
			}
			return user;
		}
		else
		{
			User user = (User) searchById(inAccount);
			return user;
		}
	}

	protected GroupSearcher getGroupSearcher()
	{
		return (GroupSearcher) getSearcherManager().getSearcher(getCatalogId(), "group");
	}

	/**
	 * @deprecate use standard field search API
	 */
	public User getUserByEmail(String inEmail)
	{
		User target = null;
		if (inEmail != null)
		{
			inEmail = inEmail.trim();
			// Raw term queries, not match(): match() follows the field XML (analyzed -> "email.sort"), but each index keeps
			// the mapping it was created with (not_analyzed "email", or analyzed with "email.exact"), and the XML has flipped
			// between the two, so a mismatch found nobody. Query both mappings; a missing field just matches nothing.
			// Both spellings too: records saved before saveData/saveAllData normalized them may still be upper case.
			String lower = inEmail.toLowerCase();
			BoolQueryBuilder any = QueryBuilders.boolQuery()
				.should(QueryBuilders.termQuery("email", inEmail)).should(QueryBuilders.termQuery("email", lower))
				.should(QueryBuilders.termQuery("email.exact", inEmail)).should(QueryBuilders.termQuery("email.exact", lower));
			SearchResponse response = getClient().prepareSearch(toId(getCatalogId())).setTypes(getSearchType()).setQuery(any).setSize(10).get();
			for (SearchHit hit : response.getHits().getHits())
			{
				User user = (User) loadData((Data) searchById(hit.getId()));
				if (user != null && (target == null || (!target.isEnabled() && user.isEnabled())))
				{
					target = user;
				}
			}
			if (target == null)
			{
				log.info("User not found: " + inEmail);
			}
		}
		return target;
	}

	public HitTracker getUsersInGroup(Group inGroup)
	{
		SearchQuery query = createSearchQuery();
		if (inGroup == null)
		{
			throw new OpenEditException("No group found");
		}
		query.addExact("groups", inGroup.getId());
		// query.setSortBy("idsorted");
		HitTracker tracker = search(query);
		// log.info(tracker.size());
		return tracker;
	}

	public void saveUsers(List userstosave, User inUser)
	{
		saveAllData(userstosave, inUser);
	}

	// Email is the login key and a case-sensitive keyword field: store it lower case so
	// getUserByEmail finds the user whatever the sign-in form typed.
	protected void normalizeEmail(Data inData)
	{
		String email = inData.get("email");
		if (email != null)
		{
			inData.setValue("email", email.trim().toLowerCase());
		}
	}

	@Override
	public void saveData(Data inData, User inUser)
	{
		normalizeEmail(inData);
		super.saveData(inData, inUser);
	}

	@Override
	public void saveAllData(Collection<Data> inAll, User inUser)
	{
		for (Data data : inAll)
		{
			normalizeEmail(data);
		}
		super.saveAllData(inAll, inUser);
	}

	@Override
	public StringEncryption getStringEncryption()
	{
		return getXmlUserArchive().getStringEncryption();
	}

	@Override
	public String encryptPassword(User inUser)
	{
		return getXmlUserArchive().encryptPassword(inUser);
	}

	@Override
	public String decryptPassword(User inUser)
	{
		return getXmlUserArchive().decryptPassword(inUser);
	}

	@Override
	public boolean initialize()
	{
		// TODO Auto-generated method stub
		return super.initialize();
	}
}
