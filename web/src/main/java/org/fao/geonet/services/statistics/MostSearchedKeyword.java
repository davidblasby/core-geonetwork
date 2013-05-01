package org.fao.geonet.services.statistics;

import jeeves.resources.dbms.Dbms;
import jeeves.server.ServiceConfig;
import jeeves.server.context.ServiceContext;
import jeeves.utils.Log;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.services.NotInReadOnlyModeService;
import org.jdom.Element;

/**
 * Service to get the db-stored requests most searched keyword.
 * made a java service because number of results can be filtered out based on input parameter
 * @author nicolas Ribot
 *
 */
public class MostSearchedKeyword extends NotInReadOnlyModeService{
	private String luceneTermFieldsToExclude;
	private int maxHits;
	//--------------------------------------------------------------------------
	//---
	//--- Init
	//---
	//--------------------------------------------------------------------------

	public void init(String appPath, ServiceConfig params) throws Exception	{
        super.init(appPath, params);
		luceneTermFieldsToExclude = params.getValue("luceneTermFieldsToExclude");
		maxHits = Integer.parseInt(params.getValue("maxHits"));
	}

	//--------------------------------------------------------------------------
	//---
	//--- Service
	//---
	//--------------------------------------------------------------------------
    @Override
	public Element serviceSpecificExec(Element params, ServiceContext context) throws Exception {
        boolean readOnlyMode = super.exec(params, context) == null;
        if(readOnlyMode) {
            return null;
        }
		Dbms dbms = (Dbms) context.getResourceManager().open(Geonet.Res.MAIN_DB);

        String query = "select termtext, count(*) as cnt from ";
        query += "params ";
		if (luceneTermFieldsToExclude != null && luceneTermFieldsToExclude.length() > 0) {
			query += " where length(termtext) > 0 and termField not in (" + luceneTermFieldsToExclude + ")";
		}
        query += " group by termtext ";
        query += "having count(*) > 1 ";
        query += "order by cnt desc";

        if(Log.isDebugEnabled(Geonet.SEARCH_LOGGER)) Log.debug(Geonet.SEARCH_LOGGER, "query: " + query);

        MostSearchedResponse mostSearchedResponse = new MostSearchedResponse();
		return mostSearchedResponse.createResponse(maxHits, dbms, query);
	}
}
