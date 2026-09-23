package org.entermediadb.elasticsearch.searchers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.asset.importer.CsvImporter;
import org.entermediadb.data.DataArchive;
import org.entermediadb.elasticsearch.SearchHitData;
import org.entermediadb.scripts.ScriptLogger;
import org.openedit.Data;
import org.openedit.OpenEditException;
import org.openedit.data.PropertyDetails;
import org.openedit.data.PropertyDetailsArchive;
import org.openedit.data.Reloadable;
import org.openedit.hittracker.HitTracker;
import org.openedit.locks.Lock;
import org.openedit.page.Page;
import org.openedit.users.User;
import org.openedit.xml.ElementData;
import org.openedit.xml.XmlSearcher;

public class ElasticListSearcher extends BaseElasticSearcher implements Reloadable
{
	protected Log log = LogFactory.getLog(ElasticListSearcher.class);
	protected DataArchive fieldDataArchive; // lazy loaded
	protected String fieldPrefix;
	protected String fieldDataFileName;
	// protected XmlFile fieldXmlFile;
	protected XmlSearcher fieldXmlSearcher;

	protected boolean fieldSaveToXml = false;

	public boolean isSaveToXml()
	{
		return fieldSaveToXml;
	}

	public void setSaveToXml(boolean inSaveToXml)
	{
		fieldSaveToXml = inSaveToXml;
	}

	public XmlSearcher getXmlSearcher()
	{

		if (fieldXmlSearcher.getCatalogId() == null)
		{
			fieldXmlSearcher.setCatalogId(getCatalogId());
			fieldXmlSearcher.setSearchType(getSearchType());
			PropertyDetailsArchive newarchive = getSearcherManager().getPropertyDetailsArchive(getCatalogId());
			fieldXmlSearcher.setPropertyDetailsArchive(newarchive);
		}
		// fieldXmlSearcher.setCacheManager(null);//Important cb: Why in the world would
		// you want always create new caches?
		return fieldXmlSearcher;
	}

	public void setXmlSearcher(XmlSearcher inXmlSearcher)
	{
		fieldXmlSearcher = inXmlSearcher;
	}

	/**
	 * @override
	 */
	protected boolean isTrackEdits()
	{
		if (getDetail("emrecordstatus") != null)
		{
			return true;
		}
		return false;
	}

	@Override
	public String getIndexId()
	{
		if (isSaveToXml())
		{
			return getXmlSearcher().getIndexId();
		}
		else
		{
			return super.getIndexId();
		}
	}

	public void clearIndex()
	{
		super.clearIndex();
		if (isSaveToXml())
		{
			getXmlSearcher().clearIndex();
		}
	}

	public String getDataFileName()
	{
		if (fieldDataFileName == null)
		{
			fieldDataFileName = getSearchType() + ".xml";
		}
		return fieldDataFileName;
	}

	public void setDataFileName(String inName)
	{
		fieldDataFileName = inName;
	}

	public Data createNewData()
	{
		if (getNewDataName() == null)
		{
			ElementData data = new ElementData();

			return data;
		}
		return (Data) getModuleManager().getBean(getNewDataName());
	}

	public synchronized void reindexXml() throws OpenEditException
	{
		getXmlSearcher().reIndexAll();
		HitTracker settings = getXmlSearcher().getAllHits();

		log.info("Reindex XML " + settings.size() + " " + getSearchType());
		Collection toindex = new ArrayList();
		for (Iterator iterator = settings.iterator(); iterator.hasNext();)
		{
			ElementData data = (ElementData) iterator.next();
			if (data.getId() == null || data.getId().isEmpty())
			{
				continue;
			}
			toindex.add(data); // loadData? nah
			// log.info(data.getName());
			if (toindex.size() > 1000)
			{
				updateIndex(toindex, null);
				toindex.clear();
			}
		}
		updateIndex(toindex, null);
		flushChanges();

		Page defaults = getPageManager().getPage("/" + getCatalogId() + "/data/lists/" + getSearchType() + ".csv");
		if( defaults.exists())		
		{
			//I want to read in CSV files from the catalog/list/*.csv from ElasticListSearcher.reindexXml it should look for a csv file in the same way it looks for an xml file. We can use our standard CSV import tool: 
			CsvImporter csvimporter = (CsvImporter)getModuleManager().getBean(getCatalogId(),"csvImporter",false);	
			csvimporter.setSearcher(this);
			csvimporter.setImportPage(defaults);
			ScriptLogger logger = new ScriptLogger();
			csvimporter.setLog(logger);
			csvimporter.setMakeId(false);
			//csvimporter.setNewdDetailPrefix("user"); //In case new ids come in
			csvimporter.importData();
		}



	}

	@Override
	public void resetData()
	{
		super.resetData();
		reindexXml();
	}

	@Override
	public void deleteAll(Collection inBuffer, User inUser)
	{
		super.deleteAll(inBuffer, inUser);
		if (isSaveToXml())
		{
			getXmlSearcher().deleteAll(inUser);
		}

	}

	public void delete(Data inData, User inUser)
	{
		if (inData instanceof SearchHitData)
		{
			inData = (Data) searchById(inData.getId());
		}
		if (inData == null || inData.getId() == null)
		{
			throw new OpenEditException("Cannot delete null data.");
		}
		if (isSaveToXml())
		{
			Lock lock = getLockManager().lock(getSearchType() + "/" + inData.getSourcePath(), "admin");
			try
			{
				getXmlSearcher().delete(inData, inUser);
				super.delete(inData, inUser);
			}
			finally
			{
				getLockManager().release(lock);
			}
		}
		else
		{
			super.delete(inData, inUser);
		}
		// Remove from Index
	}

	// This is the main APU for saving and updates to the index
	public void saveAllData(Collection<Data> inAll, User inUser)
	{
		PropertyDetails details = getPropertyDetailsArchive().getPropertyDetailsCached(getSearchType());

		for (Object object : inAll)
		{
			Data data = (Data) object;
			try
			{
				saveToElasticSearch(details, data, false, inUser); // Cant use bulk operations because id wont be set
			}
			catch (Throwable ex)
			{
				log.error("problem saving " + data.getId(), ex);
				throw new OpenEditException(ex);
			}
		}
		if (isSaveToXml())
		{
			getXmlSearcher().saveAllData(inAll, inUser);
		}
	}

	public void saveData(Data inData, User inUser)
	{
		// update the index
		PropertyDetails details = getPropertyDetails();

		try
		{
			saveToElasticSearch(details, inData, false, inUser);
			if (isSaveToXml())
			{
				getXmlSearcher().saveData(inData, inUser);
			}
			clearIndex();
		}
		catch (Throwable ex)
		{
			log.error("problem saving " + inData.getId() + " searchtype:" + getSearchType() + " " + ex.getMessage());
			throw new OpenEditException(ex);
		}
	}

	public Object searchById(String inId)
	{
		// return getXmlSearcher().searchById(inId);
		return super.searchById(inId); // Dont ever read from XML, just write
	}

	// public PropertyDetails getPropertyDetails() {
	// return getXmlSearcher().getPropertyDetails();
	//
	// }

	@Override
	public boolean initialize()
	{
		if (!tableExists())
		{
			reindexXml();
			return true;
		}
		return false;
	}

}
