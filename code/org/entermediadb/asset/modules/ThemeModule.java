package org.entermediadb.asset.modules;

import java.awt.Dimension;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Collection;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.asset.Asset;
import org.entermediadb.asset.MediaArchive;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.data.SearcherManager;
import org.openedit.generators.Output;
import org.openedit.page.Page;
import org.openedit.page.PageRequestKeys;
import org.openedit.page.PageSettings;
import org.openedit.repository.ContentItem;
import org.openedit.util.CSSUtils;
import org.openedit.util.RequestUtils;
import org.openedit.util.URLUtilities;

public class ThemeModule extends BaseMediaModule
{
	private static final Log log = LogFactory.getLog(ThemeModule.class);

	protected RequestUtils fieldRequestUtils;
	protected SearcherManager fieldSearcherManager;

	public RequestUtils getRequestUtils()
	{
		return fieldRequestUtils;
	}

	public void setRequestUtils(RequestUtils inRequestUtils)
	{
		fieldRequestUtils = inRequestUtils;
	}

	public void saveAllCustomThemes(WebPageRequest inReq) throws UnsupportedEncodingException
	{
		MediaArchive archive = getMediaArchive(inReq);
		// Process all the themes
		String catalogid = inReq.findPathValue("catalogid");
		Collection themes = getSearcherManager().query(catalogid, "theme").all().search();
		String appid = inReq.findValue("applicationid");

		for (Iterator iterator = themes.iterator(); iterator.hasNext();)
		{
			Data theme = (Data) iterator.next();

			String inputfile = theme.get("templatecss");
			if (inputfile == null)
			{
				inputfile = "/${applicationid}/theme/styles/overridestemplate.css";
			}
			inputfile = inReq.getContentPage().replaceProperty(inputfile);

			Page page = getPageManager().getPage(inputfile);

			WebPageRequest req = getRequestUtils().createPageRequest(page, inReq.getRequest(), inReq.getResponse(), inReq.getUser(), (URLUtilities) inReq.getPageValue(PageRequestKeys.URL_UTILITIES));
			String outputfile = "/" + appid + "/theme/" + theme.getId() + "/custom.css";
			ContentItem outputcContentItem = getPageManager().getContent(outputfile);
			// getPageManager().putPage(outputpage);
			// loadTheme(req);
			req.putPageValue("theme", theme);
			req.putPageValue("mediaarchive", archive);
			// req.putPageValue("numberutils", new NumberUtils());
			CSSUtils cssutils = (CSSUtils) archive.getBean("cssutils");
			req.putPageValue("cssutils", cssutils);

			URLUtilities urlUtil = (URLUtilities) inReq.getPageValue(PageRequestKeys.URL_UTILITIES);

			req.putProtectedPageValue(PageRequestKeys.HOME, urlUtil.relativeHomePrefix());
			OutputStream stream = outputcContentItem.getOutputStream();
			Writer capture = new OutputStreamWriter(stream, page.getCharacterEncoding());
			Output out = new Output(capture, null);

			page.generate(req, out);
			try
			{
				capture.flush();
				stream.close();
			}
			catch (Exception e)
			{
				log.error("Error saving theme css", e);
			}

		}
	}

	public void saveLogo(WebPageRequest inReq) throws Exception
	{
		MediaArchive archive = getMediaArchive(inReq);
		String applicationid = inReq.findValue("applicationid");
		Data theme = loadTheme(inReq);
		if (theme == null)
		{
			return;
		}
		String logoassetid = theme.get("logoasset");
		if (logoassetid != null)
		{
			Asset logoasset = archive.getAsset(logoassetid);
			if (logoasset != null)
			{
				Page logopage = archive.getOriginalDocument(logoasset);
				if (logopage != null)
				{
					Page destpage = getPageManager().getPage("/" + applicationid + "/" + theme.getId() + "/logo.png");
					if (!destpage.getPath().equals(logopage.getPath()))
					{
						getPageManager().copyPage(logopage, destpage);
						Dimension assetdimension = archive.getAssetImporter().getAssetUtilities().getImageDimensionImageIO(logopage.getContentItem());
						if (assetdimension.width > 0)
						{
							theme.setValue("logowidth", assetdimension.width);
						}
						if (assetdimension.height > 0)
						{
							theme.setValue("logoheight", assetdimension.height);

						}
						archive.saveData("theme", theme);
					}

				}
			}
		}
	}

	public void saveTheme(WebPageRequest inReq)
	{
		String catalogid = inReq.findPathValue("catalogid");
		Searcher themeSearcher = getSearcherManager().getSearcher(catalogid, "theme");
		String owner = inReq.findValue("applicationid");
		if (owner != null)
		{
			Data theme = (Data) themeSearcher.searchById(owner + "theme");
			if (theme == null)
			{
				theme = themeSearcher.createNewData();
				theme.setId(owner + "theme");
				theme.setSourcePath("themes");
			}
			String[] fields = inReq.getRequestParameters("field");
			themeSearcher.updateData(inReq, fields, theme);
			theme.setId(owner + "theme");
			themeSearcher.saveData(theme, inReq.getUser());
			inReq.putPageValue("theme", theme);
		}
	}

	public Data loadTheme(WebPageRequest inReq)
	{
		String catalogid = inReq.findPathValue("catalogid");
		Searcher themeSearcher = getSearcherManager().getSearcher(catalogid, "theme");
		String themeid = inReq.getRequestParameter("themeid");
		Data theme = (Data) themeSearcher.searchById(themeid);
		if (theme != null)
		{
			inReq.putPageValue("theme", theme);
		}
		return theme;
	}

	public void loadCurrentTheme(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);

		String themeid = null;
		if (inReq.getUserProfile() != null)
		{
			themeid = inReq.getUserProfile().get("themeid");
		}
		if (themeid == null)
		{
			themeid = inReq.findPathValue("themeid");
		}
		if (themeid != null && "defaulttheme".equals(themeid))
		{
			themeid = null;
		}
		if (themeid == null)
		{
			themeid = "theme";
		}
		Data theme = archive.getCachedData("theme", themeid);
		if (theme != null)
		{
			inReq.putPageValue("currenttheme", theme);
		}

		inReq.putPageValue("themeid", themeid);
		String appid = inReq.findValue("applicationid");
		inReq.putPageValue("themeprefix", "/" + appid + "/" + themeid);
	}

	public void changeTheme(WebPageRequest inReq)
	{
		String appid = inReq.findValue("applicationid");
		String themeid = inReq.getRequestParameter("themeid");
		PageSettings xconf = getPageManager().getPageSettingsManager().getPageSettings("/" + appid + "/_site.xconf");

		xconf.setProperty("themeid", themeid);
		getPageManager().getPageSettingsManager().saveSetting(xconf);
		getPageManager().clearCache();

		try
		{

			saveAllCustomThemes(inReq);
		}
		catch (Exception e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void resetThemes(WebPageRequest inReq)
	{
		String catalogid = inReq.findPathValue("catalogid");
		Searcher themeSearcher = getSearcherManager().getSearcher(catalogid, "theme");
		String themeid = inReq.getRequestParameter("themeid");
		themeSearcher.resetMappings();
		themeSearcher.reindexInternal();
		inReq.putPageValue("message", "Theme reset");
		changeTheme(inReq);
		// Data theme = (Data) themeSearcher.searchById(themeid);
		// if (theme != null) {
		// inReq.putPageValue("theme", theme);
		// }
		// return theme;
	}

}
