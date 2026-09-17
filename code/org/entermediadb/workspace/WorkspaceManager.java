package org.entermediadb.workspace;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Attribute;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Requests;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.elasticsearch.ElasticNodeManager;
import org.entermediadb.elasticsearch.SearchHitData;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.OpenEditException;
import org.openedit.data.PropertyDetail;
import org.openedit.data.PropertyDetails;
import org.openedit.data.PropertyDetailsArchive;
import org.openedit.data.Searcher;
import org.openedit.data.SearcherManager;
import org.openedit.hittracker.HitTracker;
import org.openedit.modules.translations.LanguageMap;
import org.openedit.node.NodeManager;
import org.openedit.page.Page;
import org.openedit.page.PageProperty;
import org.openedit.page.PageSettings;
import org.openedit.page.manage.PageManager;
import org.openedit.util.PageZipUtil;
import org.openedit.util.PathUtilities;
import org.openedit.util.Replacer;
import org.openedit.util.XmlUtil;
import org.openedit.util.ZipUtil;
import org.openedit.xml.ElementData;
import org.openedit.xml.XmlArchive;
import org.openedit.xml.XmlFile;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingJsonFactory;

public class WorkspaceManager
{
	private static final Log log = LogFactory.getLog(WorkspaceManager.class);

	protected SearcherManager fieldSearcherManager;
	protected PageManager fieldPageManager;
	protected XmlArchive fieldXmlArchive;

	public XmlArchive getXmlArchive()
	{
		return fieldXmlArchive;
	}

	public void setXmlArchive(XmlArchive inXmlArchive)
	{
		fieldXmlArchive = inXmlArchive;
	}

	public SearcherManager getSearcherManager()
	{
		return fieldSearcherManager;
	}

	public void setSearcherManager(SearcherManager inSearcherManager)
	{
		fieldSearcherManager = inSearcherManager;
	}

	public PageManager getPageManager()
	{
		return fieldPageManager;
	}

	public void setPageManager(PageManager inPageManager)
	{
		fieldPageManager = inPageManager;
	}

	public MediaArchive getMediaArchive(String inCatalogId)
	{
		MediaArchive archive = (MediaArchive) getSearcherManager().getModuleManager().getBean(inCatalogId, "mediaArchive");
		return archive;
	}

	public void exportWorkspace(String apppath, OutputStream inOut) throws Exception
	{
		Page apppage = getPageManager().getPage(apppath);
		String catalogid = apppage.get("catalogid");
		String appid = apppage.get("applicationid");

		PageZipUtil pageZipUtil = new PageZipUtil(getPageManager());
		// pageZipUtil.setFolderToStripOnZip(false);

		ZipOutputStream finalZip = new ZipOutputStream(inOut);
		Collection files = getSearcherManager().getList("media", "workspacefiles");
		for (Iterator iterator = files.iterator(); iterator.hasNext();)
		{
			Data folder = (Data) iterator.next();
			String path = folder.getName();
			path = apppage.replaceProperty(path);
			pageZipUtil.zip(path, finalZip);
		}
		Element root = DocumentHelper.createElement("application");
		root.addElement("applicationid").addAttribute("id", appid);
		root.addElement("catalogid").addAttribute("id", catalogid);
		root.addAttribute("version", "9");

		Data app = (Data) getSearcherManager().getSearcher(catalogid, "app").searchByField("deploypath", "/" + appid);
		// Data app = getSearcherManager().getData(catalogid, "app", appid);
		if (app != null)
		{
			root.addElement("name").setText(app.getName());
		}
		// root.addElement("deploypath").addAttribute("id",catalogid);
		pageZipUtil.addTozip(root.asXML(), ".emapp.xml", finalZip);

		finalZip.close();
	}

	public String createTable(String catalogid, String tablename, String inPrefix)
	{
		String searchtype = PathUtilities.makeId(tablename);
		searchtype = searchtype.toLowerCase();
		PropertyDetailsArchive archive = getSearcherManager().getPropertyDetailsArchive(catalogid);
		String path = "/WEB-INF/data/" + catalogid + "/fields/" + searchtype + ".xml";
		String pathbase = "/" + catalogid + "/fields/" + searchtype + ".xml";
		String base = "/catalog/fields/" + searchtype + ".xml";
		
		if (getPageManager().getPage(path).exists() || 
			getPageManager().getPage(pathbase).exists() ||
			getPageManager().getPage(base).exists())
		{
			return searchtype;
		}
		// Create a new one
		PropertyDetails details = new PropertyDetails(archive, searchtype);
		// details.setDetails(defaultdetails.getDetails()); //Entities have everything
		// in the folder
		if (details.getBeanName() == null)
		{
			details.setBeanName("dataSearcher");
		}
		String file = "/WEB-INF/data/" + catalogid + "/fields/" + tablename + ".xml";

		archive.savePropertyDetails(details, tablename, null, file);

		return searchtype;
	}

	public void saveModule(String catalogid, String appid, Data module)
	{
		saveModule(catalogid, appid, module, false);
	}

	public void saveModule(String catalogid, String appid, Data module, boolean verify)
	{
		if (module == null)
		{
			throw new OpenEditException("Invalid module id");
		}
		String mid = createModuleFallbacks(appid, module);
		String templatepermissionfields = "/" + catalogid + "/configuration/baseentitytemplate.xml";
		Page template = getPageManager().getPage(templatepermissionfields);

		if (!mid.equals("asset"))
		{
			/** DATABASE STUFF **/
			// is Entity?
			if (Boolean.parseBoolean(module.get("isentity")))
			{
				if (template.exists())
				{
					Page destination = getPageManager().getPage("/WEB-INF/data/" + catalogid + "/fields/" + module.getId() + "/baseentity.xml");
					getPageManager().copyPage(template, destination); // Always update these
				}
			}
		}
		// add settings menu
		createTable(catalogid, module.getId(), module.getId());
		getSearcherManager().getPropertyDetailsArchive(catalogid).clearCache(); /// This is slow
		getMediaArchive(catalogid).getPermissionManager().queuePermissionCheck((MultiValued) module);
		getMediaArchive(catalogid).saveData("module", module);
	}

	public String createModuleFallbacks(String appid, Data module)
	{
		return createModuleFallbacks(appid, module, false);
	}

	public String createModuleFallbacks(String appid, Data module, boolean force)
	{
		Page modulehome = getPageManager().getPage("/" + appid + "/views/modules/" + module.getId() + "/_site.xconf");

		PageSettings homesettings = modulehome.getPageSettings();

		boolean changed = false;

		if (changeValue(homesettings, "fallbackdirectory", "/${applicationid}/views/modules/default", force))
		{
			changed = true;
		}
		if (changeValue(homesettings, "module", module.getId(), force))
		{
			changed = true;
		}

		Page settings = getPageManager().getPage("/" + appid + "/views/settings/modules/" + module.getId() + "/_site.xconf");
		PageSettings modulesettings = settings.getPageSettings();
		if (changeValue(modulesettings, "fallbackdirectory", "/${applicationid}/views/settings/modules/default", force))
		{
			changed = true;
		}
		if (changeValue(modulesettings, "module", module.getId(), force))
		{
			changed = true;
		}

		if (changed)
		{
			getPageManager().getPageSettingsManager().saveSetting(homesettings);
			getPageManager().getPageSettingsManager().saveSetting(modulesettings);
		}
		return module.getId();
	}

	boolean changeValue(PageSettings inSettings, String inKey, String inValue, boolean force)
	{
		PageProperty prop = inSettings.getProperty(inKey);
		if (prop != null && !force)
		{
			String propvalue = prop.getValue();
			if (propvalue != null && propvalue.startsWith("/community/default/"))
			{
				return false; // Don't override if fallback to community defaults, because they have custom views
			}
			if (inValue.equals(propvalue))
			{
				return false;

			}
		}

		prop = new PageProperty(inKey); // Don't ever update existing properties
		prop.setValue(inValue);
		inSettings.putProperty(prop);
		return true;

	}

	public void createMediaDbModule(String inCatalogId, Data inModule)
	{
		// Data setup
		Data setting = getSearcherManager().getData(inCatalogId, "catalogsettings", "mediadbappid");
		String mediadb = setting.get("value");
		if (mediadb == null)
		{
			throw new OpenEditException("Must set the mediadbappid id");
		}

		Replacer replacer = new Replacer();
		Map lookup = new HashMap();
		lookup.put("mediadbappid", mediadb);
		lookup.put("moduleid", inModule.getId());
		lookup.put("module", inModule);
		lookup.put("modulename", inModule.getName("en"));

		Searcher sectionSearcher = getSearcherManager().getSearcher(inCatalogId, "docsection");
		Data section = (Data) sectionSearcher.searchById("module" + inModule.getId());

		LanguageMap names = new LanguageMap();
		names.setText("en", inModule.getName("en"));

		if (section == null)
		{
			section = sectionSearcher.createNewData();
			section.setId("module" + inModule.getId());
			section.setValue("name", names);
			sectionSearcher.saveData(section, null);

		}
		if (!inModule.getName().equals(section.getName()))
		{
			section.setValue("name", names);
			sectionSearcher.saveData(section, null);
		}
		Searcher endpointSearcher = getSearcherManager().getSearcher(inCatalogId, "endpoint");
		Collection templates = getSearcherManager().getList(inCatalogId, "endpointmoduletemplate");

		// TODO: Use a new smart merge Searcher
		for (Iterator iterator = templates.iterator(); iterator.hasNext();)
		{
			Data row = (Data) iterator.next();
			Data endpoint = (Data) endpointSearcher.searchById(section.getId() + row.getId());
			if (endpoint == null)
			{
				endpoint = endpointSearcher.createNewData();
				// endpoint.setProperties(row.getProperties());
				for (Iterator iterator2 = row.keySet().iterator(); iterator2.hasNext();)
				{
					String key = (String) iterator2.next();
					String val = row.get(key);
					val = replacer.replace(val, lookup);
					endpoint.setProperty(key, val);
				}

				endpoint.setId(section.getId() + row.getId());
				endpoint.setProperty("docsection", section.getId());
				endpointSearcher.saveData(endpoint, null);
			}
		}

		// Files
		String settingspath = "/" + mediadb + "/services/module/" + inModule.getId() + "/_site.xconf";
		// if (!getPageManager().getRepository().doesExist(settingspath))
		{
			Page home = getPageManager().getPage(settingspath);
			PageSettings homesettings = home.getPageSettings();
			homesettings.setProperty("module", inModule.getId());
			PageProperty prop = new PageProperty("fallbackprop");

			// This might be an existing fallback
			String parent = "/" + mediadb + "/services/module/default";
			Collection<PageSettings> fallbacks = homesettings.getFallbackParents();
			for (PageSettings fallback : fallbacks)
			{
				String path = fallback.getParentPath();
				if (getPageManager().getRepository().doesExist(path) && !path.endsWith("default"))
				{
					// Use this one
					parent = path;
					break;
				}
			}
			prop.setValue(parent);
			homesettings.putProperty(prop);
			prop = new PageProperty("searchtype");
			prop.setValue(inModule.getId());
			homesettings.putProperty(prop);
			getPageManager().getPageSettingsManager().saveSetting(homesettings);
		}

		getPageManager().clearCache();
	}

	public void createMediaDbAiFunctionEndPoints(String inCatalogId)
	{
		Searcher endpointSearcher = getSearcherManager().getSearcher(inCatalogId, "endpoint");
		Searcher functionsSearcher = getSearcherManager().getSearcher(inCatalogId, "aifunction");

		MediaArchive archive = getMediaArchive(inCatalogId);
		Data section = archive.getCachedData("docsection", "aifunctions");
		if (section == null)
		{
			section = archive.getSearcher("docsection").createNewData();
			section.setId("aifunctions");
			section.setName("AI Functions");
			archive.saveData("docsection", section);
		}

		HitTracker<Data> moduleids = archive.getList("module");
		Data entity = archive.query("modulesearch").put("searchtypes", moduleids.collectValues("id")).exact("entityembeddingstatus", "embedded").searchOne();

		if (entity == null)
		{
			return;
		}
		Data entitymodule = archive.getCachedData("module", entity.get("entitysourcetype"));

		JSONObject request = new JSONObject();

		request.put("channel", "testchannel");
		request.put("message", "What is this all about?");

		String siteid = PathUtilities.extractDirectoryPath(inCatalogId);
		// request.put("chatapplicationid", siteid + "/find");
		request.put("entityname", entity.getName());
		request.put("entityid", entity.getId());
		request.put("entitymoduleid", entitymodule.getId());

		Collection tosave = new ArrayList();
		String mediadbhome = "/" + archive.getCatalogSettingValue("mediadbappid");

		HitTracker existing = endpointSearcher.query().exact("docsection", section.getId()).search();
		Collection existids = existing.collectValues("id");

		Collection all = functionsSearcher.query().all().search();
		for (Iterator iterator = all.iterator(); iterator.hasNext();)
		{
			Data function = (Data) iterator.next();
			if (existids.contains(function.getId()))
			{
				continue;
			}
			Data endpoint = endpointSearcher.createNewData();
			endpoint.setName(function.getName());
			endpoint.setId(function.getId());
			endpoint.setValue("url", mediadbhome + "/services/ai/" + function.getId());

			if (function.get("samplemesage") != null)
			{
				request.put("message", function.get("samplemesage"));
			}
			endpoint.setValue("samplerequest", request.toJSONString());
			endpoint.setValue("httpmethod", "POST");
			endpoint.setProperty("docsection", section.getId());
			tosave.add(endpoint);
		}
		endpointSearcher.saveAllData(tosave, null);
		/*
		 * <endpoint id="search" name="Search for ${modulename}"
		 * url="/${mediadbappid}/services/module/${moduleid}/search" httpmethod="POST"> <samplerequest>
		 * <![CDATA[{ "page": "1", "hitsperpage":"20", "query": { "terms":[{ "field": "id", "operator":
		 * "matches", "value": "*" }] } } ]]></samplerequest> </endpoint>
		 */
	}

	protected void copyXml(String catalogid, String inTemplatePath, String inEndingPath, Data module)
	{
		if (!getPageManager().getPage(inEndingPath).exists())
		{
			XmlFile file = getXmlArchive().getXml(inTemplatePath);
			for (Iterator iterator = file.getElements("property"); iterator.hasNext();)
			{
				Element row = (Element) iterator.next();
				for (Iterator iterator2 = row.attributeIterator(); iterator2.hasNext();)
				{
					Attribute attr = (Attribute) iterator2.next();
					String val = attr.getValue();
					val = val.replace("default", module.getId());
					attr.setValue(val);
				}
				// String id = row.attributeValue("id");
				// row.addAttribute("id", id);
				// row.addAttribute("module", module.getId());
				//
				// String parentid = row.attributeValue("parentid");
				// if( parentid != null )
				// {
				// parentid = parentid.replace("default", module.getId());
				// row.addAttribute("parentid", parentid);
				// }
			}
			// Now copy the views default list
			file.setPath(inEndingPath);
			getXmlArchive().saveXml(file, null);
		}
	}

	public void fixFiles(Page inFolder, String inOldCatalogId)
	{
		Page upload = getPageManager().getPage(inFolder.getPath() + "/WEB-INF/data/" + inOldCatalogId + "/lists/settingsrole.xml");

		XmlUtil util = new XmlUtil();
		Element root = util.getXml(upload.getReader(), "utf-8");
		for (Iterator iterator = root.elementIterator(); iterator.hasNext();)
		{
			Element row = (Element) iterator.next();
			StringBuffer perms = new StringBuffer();
			List atrribs = new ArrayList(row.attributes());

			for (Iterator iterator2 = row.attributes().iterator(); iterator2.hasNext();)
			{
				Attribute attr = (Attribute) iterator2.next();
				if (Boolean.valueOf(attr.getValue()))
				{
					atrribs.remove(attr);
					if (perms.length() > 0)
					{
						perms.append("|");
					}
					perms.append(attr.getQualifiedName());
				}
			}
			row.setAttributes(atrribs);
			row.addAttribute("permissions", perms.toString());

		}
		OutputStream out = getPageManager().saveToStream(upload);
		util.saveXml(root, out, "utf-8");
	}

	public void deployUploadedApp(String inAppcatalogid, String inDestinationAppId, Page zip)
	{
		Page dest = getPageManager().getPage("/WEB-INF/temp/appunzip");
		try
		{
			getPageManager().removePage(dest);

			new ZipUtil().unzip(zip.getContentItem().getAbsolutePath(), dest.getContentItem().getAbsolutePath());

			Page def = getPageManager().getPage(dest.getPath() + "/.emapp.xml");
			Element root = new XmlUtil().getXml(def.getReader(), "UTF-8");
			String oldapplicationid = root.element("applicationid").attributeValue("id");
			String oldcatalogid = root.element("catalogid").attributeValue("id");

			String version = root.attributeValue("version");
			if (version == null || version.equals("8"))
			{
				// fix settingsroles.xml
				fixFiles(dest, oldcatalogid);
			}

			// We need to delete the incoming list of apps
			Page appdata = getPageManager().getPage(dest.getPath() + "/WEB-INF/data/" + oldcatalogid + "/lists/app/custom.xml");
			getPageManager().removePage(appdata);

			// move the files in place
			Page apphome = getPageManager().getPage(dest.getPath() + "/" + oldapplicationid);
			Page appdest = getPageManager().getPage("/" + inDestinationAppId);
			getPageManager().removePage(appdest);
			getPageManager().copyPage(apphome, appdest);

			// tweak the xconf
			PageSettings homesettings = getPageManager().getPageSettingsManager().getPageSettings("/" + inDestinationAppId + "/_site.xconf");
			homesettings.setProperty("applicationid", inDestinationAppId);
			homesettings.setProperty("catalogid", inAppcatalogid);
			if (homesettings.getProperty("fallbackprop") == null)
			{
				homesettings.setProperty("fallbackprop", "/emshare");
			}
			getPageManager().getPageSettingsManager().saveSetting(homesettings);

			Page cataloghome = getPageManager().getPage(dest.getPath() + "/" + oldcatalogid);
			if (cataloghome.exists())
			{
				Page catalogdest = getPageManager().getPage("/" + inAppcatalogid);
				getPageManager().removePage(catalogdest);
				getPageManager().copyPage(cataloghome, catalogdest);

				PageSettings catsettings = getPageManager().getPageSettingsManager().getPageSettings("/" + inAppcatalogid + "/_site.xconf");
				catsettings.setProperty("catalogid", inAppcatalogid);
				catsettings.setProperty("fallbackprop", "/media/catalog");

				getPageManager().getPageSettingsManager().saveSetting(catsettings);
			}

			Page dataold = getPageManager().getPage(dest.getPath() + "/WEB-INF/data/" + oldcatalogid);
			Page datadest = getPageManager().getPage("/WEB-INF/data/" + inAppcatalogid);
			if (dataold.exists())
			{
				getPageManager().removePage(datadest);
				getPageManager().copyPage(dataold, datadest);
			}
			// Save the app data
			Searcher searcher = getSearcherManager().getSearcher(inAppcatalogid, "app");
			Data site = (Data) searcher.searchByField("deploypath", "/" + inDestinationAppId);
			if (site == null)
			{
				site = searcher.createNewData();
			}
			// String frontendid = inReq.findValue("frontendid");
			// if( frontendid == null)
			// {
			// throw new OpenEditException("frontendid was null");
			// }

			if (inDestinationAppId != null)
			{
				site.setProperty("deploypath", "/" + inDestinationAppId);
			}
			// if (catalogid != null)
			// {
			//
			// site.setProperty("appcatalogid", catalogid);
			// }

			String name = root.elementText("name");
			if (name != null)
			{
				site.setName(name);
			}

			searcher.saveData(site, null);

			Searcher catsearcher = getSearcherManager().getSearcher("system", "catalog");
			Data cat = (Data) catsearcher.searchById(inAppcatalogid);
			if (cat == null)
			{
				cat = catsearcher.createNewData();
				cat.setId(inAppcatalogid);
				catsearcher.saveData(cat, null);
			}
			MediaArchive archive = (MediaArchive) getSearcherManager().getModuleManager().getBean(inAppcatalogid, "mediaArchive");
			archive.clearAll();
			// Reset mapping
			NodeManager nodemanager = (NodeManager) getSearcherManager().getModuleManager().getBean(inAppcatalogid, "nodeManager");
			nodemanager.reindexInternal(inAppcatalogid);
			// Reset lists
			// getSearcherManager().reloadLoadedSettings(inAppcatalogid);

		}
		catch (Exception ex)
		{
			throw new OpenEditException(ex);
		}
	}

}
