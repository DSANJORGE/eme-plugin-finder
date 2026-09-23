package org.entermediadb.users;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.asset.Category;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.data.AddedPermission;
import org.openedit.CatalogEnabled;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.cache.CacheManager;
import org.openedit.data.BaseData;
import org.openedit.data.Searcher;
import org.openedit.data.SearcherManager;
import org.openedit.hittracker.HitTracker;
import org.openedit.users.Group;
import org.openedit.users.User;

public class PermissionManager implements CatalogEnabled
{

	private static final Log log = LogFactory.getLog(PermissionManager.class);

	protected SearcherManager fieldSearcherManager;

	public CacheManager getCacheManager()
	{
		return fieldCacheManager;
	}

	public void setCacheManager(CacheManager inCacheManager)
	{
		fieldCacheManager = inCacheManager;
	}

	protected CacheManager fieldCacheManager;
	protected String fieldCatalogId;

	public String getCatalogId()
	{
		return fieldCatalogId;
	}

	public void setCatalogId(String inCatalogId)
	{
		fieldCatalogId = inCatalogId;
	}

	public SearcherManager getSearcherManager()
	{
		return fieldSearcherManager;
	}

	public void setSearcherManager(SearcherManager inSearcherManager)
	{
		fieldSearcherManager = inSearcherManager;
	}

	private Searcher getSearcher(String inSearchType)
	{
		return getSearcherManager().getSearcher(getCatalogId(), inSearchType);
	}

	/**
	 * See what is available and give all permissions if needed
	 * 
	 * @param inModuleId
	 * @param inGroupId
	 * @return
	 */
	public Collection<String> calculateModulePermissions(String inModuleId, String inGroupId)
	{
		Searcher searcher = getSearcher("permissionentityassigned");
		HitTracker grouppermissions = searcher.query().exact("group", inGroupId).exact("moduleid", inModuleId).missing("entityid").search();

		HitTracker hits = null;
		Collection<String> allpermissions = new HashSet();
		for (Object typeobj : getMediaArchive().getList("permissionentitytype"))
		{
			Data type = (Data) typeobj;
			if ("views".equals(type.getId()))
			{
				hits = getMediaArchive().query("view").exact("moduleid", inModuleId).exact("systemdefined", false).sort("ordering").search();
			}
			else if ("module".equals(type.getId()))
			{
				hits = getMediaArchive().query("permissionsentity").exact("permissionentitytype", inModuleId).sort("ordering").search();
			}
			else
			{
				hits = getMediaArchive().query("permissionsentity").exact("permissionentitytype", type.getId()).sort("ordering").search();
			}
			Collection<String> existing = grouppermissions.collectValues("permissionsentity");
			Collection<String> needit = hits.collectValues("id");
			if (existing.isEmpty())
			{
				existing.addAll(needit);
			}

			allpermissions.addAll(existing);

			if (existing.containsAll(needit))
			{
				allpermissions.add(type.getId());
			}

		}
		return allpermissions;
	}

	public Collection<String> calculateEntityPermissions(String inModuleId, String inEntityId, String inGroupId)
	{
		Searcher searcher = getSearcher("permissionentityassigned");
		HitTracker modulepermissions = searcher.query().exact("group", inGroupId).exact("moduleid", inModuleId).missing("entityid").search();
		Collection<String> existingassignedmodule = modulepermissions.collectValues("permissionsentity");

		HitTracker entitypermissions = searcher.query().exact("group", inGroupId).exact("entityid", inEntityId).search(); // TODO add exact("moduleid", inModuleId)
		Collection<String> existingassignedentity = entitypermissions.collectValues("permissionsentity");

		HitTracker hits = null;
		Collection<String> allpermissions = new HashSet();
		for (Object typeobj : getMediaArchive().getList("permissionentitytype"))
		{
			Data type = (Data) typeobj;
			if ("views".equals(type.getId()))
			{
				hits = getMediaArchive().query("view").exact("moduleid", inModuleId).exact("systemdefined", false).sort("ordering").search();
			}
			else if ("module".equals(type.getId()))
			{
				hits = getMediaArchive().query("permissionsentity").exact("permissionentitytype", inModuleId).sort("ordering").search();
			}
			else
			{
				hits = getMediaArchive().query("permissionsentity").exact("permissionentitytype", type.getId()).sort("ordering").search();
			}
			Collection<String> needit = hits.collectValues("id");
			if (existingassignedentity.isEmpty() && existingassignedmodule.isEmpty())
			{
				existingassignedentity.addAll(needit); // everything
			}

			if (existingassignedentity.isEmpty())
			{
				allpermissions.addAll(existingassignedmodule);
			}
			else
			{
				allpermissions.addAll(existingassignedentity);
			}

			if (existingassignedentity.containsAll(needit)) // contains everything
			{
				allpermissions.add(type.getId()); // If it has everything add the whole section
			}

		}
		return allpermissions;
	}

	protected MediaArchive getMediaArchive()
	{
		return (MediaArchive) getSearcherManager().getModuleManager().getBean(getCatalogId(), "mediaArchive");
	}

	public void checkEntityCategoryPermission(Data inModule, MultiValued inEntity)
	{
		MediaArchive archive = getMediaArchive();
		Category rootcat = archive.getEntityManager().loadDefaultFolder(inModule, inEntity, null);
		if (rootcat == null)
		{
			return;
		}
		boolean needsupdate = false;
		String[] fieldsToCompare = {"users", "groups"};
		for (String field : fieldsToCompare)
		{
			// Get values from both the root category and the module
			Collection<String> rootValues = rootcat.getValues("viewer" + field);
			Collection<String> combinedViewers = inEntity.getValues("viewer" + field);
			// Normalize null values to empty collections
			if (rootValues == null)
			{
				rootValues = Collections.emptyList();
			}
			if (combinedViewers == null)
			{
				combinedViewers = new ArrayList();
			}
			Collection editorValues = inEntity.getValues("editor" + field);
			if (field.equals("users"))
			{
				String owner = inEntity.get("owner");
				if (owner != null)
				{
					if (!inEntity.containsValue("editor" + field, owner))
					{
						inEntity.addValue("editor" + field, owner);
						editorValues = inEntity.getValues("editor" + field);
					}
				}
			}

			if (editorValues != null)
			{
				combinedViewers.addAll(editorValues);
			}
			// Add in the owner

			// Compare values
			if (!rootValues.containsAll(combinedViewers) || !combinedViewers.containsAll(rootValues))
			{
				log.info("Custom values found: " + rootValues + ", Module values: " + combinedViewers);

				needsupdate = true;
				rootcat.setValue("viewer" + field, combinedViewers);
			}
		}
		if (needsupdate)
		{
			archive.getCategorySearcher().saveCategory(rootcat);
			archive.saveData(inModule.getId(), inEntity);

			// reindex all the submodules
			Collection enttiyviews = getMediaArchive().query("view").exact("moduleid", inModule.getId()).exact("systemdefined", "false").exact("rendertype", "entitysubmodules").cachedSearch();

			for (Iterator iterator = enttiyviews.iterator(); iterator.hasNext();)
			{
				Data data = (Data) iterator.next();
				String searchtype = data.get("rendertable"); // remote

				String renderexternalid = data.get("renderexternalid"); // join

				String renderinternalid = data.get("renderinternalid");
				if (renderinternalid == null)
				{
					renderinternalid = inModule.getId(); // can be customized
				}
				String renderinternalvalue = inEntity.get(renderinternalid);
				if (searchtype != null && renderexternalid != null && renderinternalvalue != null)
				{
					HitTracker hits = getMediaArchive().query(searchtype).exact(renderexternalid, renderinternalvalue).search();
					for (int i = 0; i < hits.getTotalPages(); i++)
					{
						hits.setPage(i + 1);
						Collection page = hits.getPageOfHits();
						getMediaArchive().saveData(searchtype, page); // Reindex
					}
				}
			}

			HitTracker assets = archive.getAssetSearcher().query().exact("category", rootcat).search();
			assets.enableBulkOperations();
			if (assets.size() > 10000)
			{
				// Do full reindex
				log.error("Required full reindex");
			}
			else
			{
				archive.getAssetSearcher().saveAllData(assets, null);
				log.info("Reindexed " + assets.size() + " assets");
			}

		}

	}

	public void queuePermissionCheck(MultiValued inModule)
	{
		MediaArchive archive = getMediaArchive();

		Category rootcat = archive.getEntityManager().loadDefaultFolderForModule(inModule, null);

		rootcat.setValue("customusers", inModule.getValue("viewusers"));
		rootcat.setValue("customgroups", inModule.getValue("viewgroups"));

		archive.saveData("category", rootcat);

		boolean needsupdate = false;
		String[] fieldsToCompare = {"users", "groups", "roles"};
		for (String field : fieldsToCompare)
		{

			Collection<String> rootValues = rootcat.getValues("viewer" + field);

			Collection combined = inModule.getValues("viewer" + field);

			// Normalize null values to empty collections
			if (rootValues == null)
			{
				rootValues = new ArrayList();
			}
			if (combined == null)
			{
				combined = new HashSet();
			}
			else
			{
				combined = new HashSet(combined);
			}

			if (inModule.getValues("editor" + field) != null)
			{
				combined.addAll(inModule.getValues("editor" + field));
			}

			// Compare values
			if (!rootValues.containsAll(combined) || !combined.containsAll(rootValues))
			{
				// permissionassigned = getPermissionManager().calculateEntityPermissions(inModule.getId(),
				// groupid);
				log.info("Root Category Values: " + rootValues + ", Module Values: " + combined);

				needsupdate = true;

			}

		}

		// String rootSecurityEnabled = (String) rootcat.get("securityenabled");
		// String moduleSecurityEnabled = (String) inModule.get("securityenabled");
		//
		// if ((rootSecurityEnabled == null && moduleSecurityEnabled != null) ||
		// (rootSecurityEnabled != null &&
		// !rootSecurityEnabled.equals(moduleSecurityEnabled))) {
		// log.info("Mismatch found for 'securityenabled' in module " +
		// inModule.getId());
		// log.info("Root Category Value: " + rootSecurityEnabled + ", Module Value: " +
		// moduleSecurityEnabled);
		// needsupdate = true;
		// }

		if (needsupdate)
		{
			inModule.setValue("permissionsupdateddate", new Date());
			archive.saveData("module", inModule);
			archive.fireSharedMediaEvent("entities/checkpermissionhistory"); // handleModulePermissionsUpdated()

		}
	}

	public void handleModulePermissionsUpdated()
	{

		MediaArchive archive = getMediaArchive();

		Searcher searcher = archive.getSearcher("permissionshistory");

		MultiValued lastruninfo = (BaseData) searcher.query().sort("dateDown").searchOne();
		Date lastrundate = null;
		if (lastruninfo == null)
		{
			lastrundate = new Date(0);
		}
		else
		{
			lastrundate = lastruninfo.getDate("date");
		}

		// This was saved by EntityModule when it noticed there is a change between the
		// rootcategory persmissions and the module permission values
		HitTracker needupdate = archive.query("module").after("permissionsupdateddate", lastrundate).search();

		StringBuffer buffer = new StringBuffer();

		for (Iterator iterator = needupdate.iterator(); iterator.hasNext();)
		{
			MultiValued module = (MultiValued) iterator.next();
			Date lastchangred = module.getDate("permissionsupdateddate");
			buffer.append("Module " + module.getId() + " Permissions being updated");
			Category rootcat = archive.getEntityManager().loadDefaultFolderForModule(module, null);

			String[] fieldsToCompare = {"users", "groups", "roles"};
			for (String field : fieldsToCompare)
			{

				Collection combined = module.getValues("viewer" + field);
				Collection editors = module.getValues("editor" + field);
				if (editors != null)
				{
					if (combined == null)
					{
						combined = new ArrayList();
					}
					combined.addAll(editors);
				}
				if (combined != null)
				{
					rootcat.setValue("viewer" + field, combined);
				}

				if (module.getValues("view" + field) != null)
				{
					rootcat.setValue("custom" + field, module.getValues("view" + field));
				}
			}

			archive.getCategorySearcher().saveCategoryTree(rootcat);

			Searcher modulesearcher = getSearcher(module.getId());

			HitTracker missingcategory = modulesearcher.query().missing("rootcategory").search();
			for (Iterator iterator2 = missingcategory.iterator(); iterator2.hasNext();)
			{
				Data data = (Data) iterator2.next();
				archive.getEntityManager().loadDefaultFolder(module, data, null);
			}

			modulesearcher.reIndexAll();

			buffer.append("Module " + module.getId() + " Permissions update completed");

			HitTracker assets = archive.getAssetSearcher().query().exact("category", rootcat).search();
			assets.enableBulkOperations();
			if (assets.size() < 25000)
			{
				archive.getAssetSearcher().saveAllData(assets, null);
			}
			else
			{
				log.error("Must manually reindex asset table");
			}

		}
		if (!needupdate.isEmpty())
		{
			Data finishedinfo = searcher.createNewData();
			finishedinfo.setValue("date", new Date());
			finishedinfo.setValue("notes", buffer.toString());
			searcher.saveData(finishedinfo);
		}
	}

	public Collection<AddedPermission> loadEntityPermissions(Data inModule, Data inEntity)
	{
		// Load all the view and edit record into a big list
		Collection<AddedPermission> alladded = new ArrayList<AddedPermission>();

		Collection<String> editorsfound = collectUsers(inEntity, true);
		Collection<String> viewersfound = collectUsers(inEntity, false);

		String owner = inEntity.get("owner");
		if (owner != null)
		{
			viewersfound.add(owner);
		}

		viewersfound.removeAll(editorsfound);
		addUsers(alladded, editorsfound, viewersfound);

		editorsfound = collectGroups(inEntity, true);
		viewersfound = collectGroups(inEntity, false);
		viewersfound.removeAll(editorsfound);
		addGroups(alladded, editorsfound, viewersfound);

		editorsfound = collectRoles(inEntity, true);
		viewersfound = collectRoles(inEntity, false);
		viewersfound.removeAll(editorsfound);
		addRoles(alladded, editorsfound, viewersfound);

		return alladded;

	}

	public Collection<AddedPermission> loadParentPermissions(Data inModule, Data inEntity)
	{
		// Load all the view and edit record into a big list
		Collection<AddedPermission> alladded = new ArrayList<AddedPermission>();

		Category entiytycategory = getMediaArchive().getEntityManager().loadDefaultFolder(inModule, inEntity, null);
		Category category = entiytycategory.getParentCategory();
		Collection<String> empty = Collections.EMPTY_LIST;

		Collection<String> viewersfound = category.collectValues("viewerusers"); // These are already combined from
		// customusers

		Collection<String> more = category.collectValues("customusers"); // These are already combined from customusers
		viewersfound.addAll(more);

		addUsers(alladded, empty, viewersfound);

		Collection<String> moregroups = category.collectValues("viewergroups");
		more = category.collectValues("customgroups"); // These are already combined from customusers
		moregroups.addAll(more);
		addGroups(alladded, empty, moregroups);

		Collection<String> moreroles = category.collectValues("viewerroles");
		addRoles(alladded, empty, moreroles);

		return alladded;

	}

	private void addUsers(Collection<AddedPermission> inAlladded, Collection<String> inEditorsfound, Collection<String> inViewersfound)
	{
		for (Iterator iterator = inEditorsfound.iterator(); iterator.hasNext();)
		{
			String userid = (String) iterator.next();
			User user = getMediaArchive().getUser(userid);
			AddedPermission added = new AddedPermission();
			added.setEditor(true);
			added.setData(user);
			added.setPermissionType("users");
			inAlladded.add(added);
		}
		for (Iterator iterator = inViewersfound.iterator(); iterator.hasNext();)
		{
			String userid = (String) iterator.next();
			User user = getMediaArchive().getUser(userid);
			AddedPermission added = new AddedPermission();
			added.setEditor(false);
			added.setData(user);
			added.setPermissionType("users");
			inAlladded.add(added);
		}
	}

	private void addRoles(Collection<AddedPermission> inAlladded, Collection<String> inEditorsfound, Collection<String> inViewersfound)
	{
		for (Iterator iterator = inEditorsfound.iterator(); iterator.hasNext();)
		{
			String id = (String) iterator.next();
			Data data = getMediaArchive().getCachedData("settingsrole", id);
			AddedPermission added = new AddedPermission();
			added.setEditor(true);
			added.setData(data);
			added.setPermissionType("groups");
			inAlladded.add(added);
		}
		for (Iterator iterator = inViewersfound.iterator(); iterator.hasNext();)
		{
			String id = (String) iterator.next();
			Data data = getMediaArchive().getCachedData("settingsrole", id);
			AddedPermission added = new AddedPermission();
			added.setEditor(false);
			added.setData(data);
			added.setPermissionType("roles");
			inAlladded.add(added);
		}
	}

	private void addGroups(Collection<AddedPermission> inAlladded, Collection<String> inEditorsfound, Collection<String> inViewersfound)
	{
		for (Iterator iterator = inEditorsfound.iterator(); iterator.hasNext();)
		{
			String id = (String) iterator.next();
			Group data = getMediaArchive().getGroup(id);
			AddedPermission added = new AddedPermission();
			added.setEditor(true);
			added.setData(data);
			added.setPermissionType("group");
			inAlladded.add(added);
		}
		for (Iterator iterator = inViewersfound.iterator(); iterator.hasNext();)
		{
			String id = (String) iterator.next();
			Group data = getMediaArchive().getGroup(id);
			AddedPermission added = new AddedPermission();
			added.setEditor(false);
			added.setData(data);
			added.setPermissionType("group");
			inAlladded.add(added);
		}
	}

	protected Collection<String> collectUsers(Data inSource, boolean inEditors)
	{
		String fieldname = "viewerusers";
		if (inEditors)
		{
			fieldname = "editorusers";
		}
		Collection<String> moreusers = inSource.getValues(fieldname);
		if (moreusers == null)
		{
			moreusers = new ArrayList(0);
		}
		return moreusers;
	}

	protected Collection<String> collectGroups(Data inSource, boolean inEditors)
	{
		String fieldname = "viewergroups";
		if (inEditors)
		{
			fieldname = "editorgroups";
		}
		Collection<String> moregroups = inSource.getValues(fieldname);
		if (moregroups == null)
		{
			moregroups = new ArrayList(0);
		}

		return moregroups;

	}

	protected Collection<String> collectRoles(Data inSource, boolean inEditors)
	{
		String fieldname = "viewerroles";
		if (inEditors)
		{
			fieldname = "editorroles";
		}
		Collection<String> more = inSource.getValues(fieldname);
		if (more == null)
		{
			more = new ArrayList(0);
		}
		return more;
	}

	public void addEntityPermissions(Data inModule, MultiValued inEntity, Map<String, String[]> inTosave)
	{
		for (Iterator iterator = inTosave.keySet().iterator(); iterator.hasNext();)
		{
			String field = (String) iterator.next();
			String[] values = inTosave.get(field);
			Collection all = Arrays.asList(values);
			inEntity.addValue(field, all);
		}

	}

}
