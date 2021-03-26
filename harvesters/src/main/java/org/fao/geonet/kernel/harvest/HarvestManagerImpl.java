//=============================================================================
//===	Copyright (C) 2001-2007 Food and Agriculture Organization of the
//===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===	and United Nations Environment Programme (UNEP)
//===
//===	This program is free software; you can redistribute it and/or modify
//===	it under the terms of the GNU General Public License as published by
//===	the Free Software Foundation; either version 2 of the License, or (at
//===	your option) any later version.
//===
//===	This program is distributed in the hope that it will be useful, but
//===	WITHOUT ANY WARRANTY; without even the implied warranty of
//===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
//===	General Public License for more details.
//===
//===	You should have received a copy of the GNU General Public License
//===	along with this program; if not, write to the Free Software
//===	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: geonetwork@osgeo.org
//==============================================================================

package org.fao.geonet.kernel.harvest;

import jeeves.server.context.ServiceContext;
import jeeves.server.dispatchers.ServiceManager;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.fao.geonet.GeonetContext;
import org.fao.geonet.constants.Edit;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.HarvestHistory;
import org.fao.geonet.domain.ISODate;
import org.fao.geonet.domain.Metadata;
import org.fao.geonet.domain.Profile;
import org.fao.geonet.exceptions.BadInputEx;
import org.fao.geonet.exceptions.JeevesException;
import org.fao.geonet.exceptions.MissingParameterEx;
import org.fao.geonet.exceptions.OperationAbortedEx;
import org.fao.geonet.kernel.AccessManager;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.HarvestInfoProvider;
import org.fao.geonet.kernel.harvest.Common.OperResult;
import org.fao.geonet.kernel.harvest.harvester.AbstractHarvester;
import org.fao.geonet.kernel.harvest.harvester.HarversterJobListener;
import org.fao.geonet.kernel.setting.HarvesterSettingsManager;
import org.fao.geonet.kernel.setting.SettingManager;
import org.fao.geonet.kernel.setting.Settings;
import org.fao.geonet.repository.HarvestHistoryRepository;
import org.fao.geonet.repository.specification.MetadataSpecs;
import org.fao.geonet.utils.Log;
import org.fao.geonet.utils.Xml;
import org.jdom.Element;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.quartz.SchedulerException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.domain.Specification;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * TODO Javadoc.
 */
public class HarvestManagerImpl implements HarvestInfoProvider, HarvestManager {

    private final List<String> summaryHarvesterSettings =
        Arrays.asList("harvesting", "node", "site", "name", "uuid",
            "url", "capabUrl", "baseUrl", "host", "useAccount",
            "ogctype", "options", "status", "info", "lastRun",
            "ownerGroup", "ownerUser");
    //---------------------------------------------------------------------------
    //---
    //--- Vars
    //---
    //---------------------------------------------------------------------------
    private HarvesterSettingsManager settingMan;
    private DataManager dataMan;
    private Path xslPath;

    /** Harvester service context */
    private ServiceContext harvesterContext;

    private boolean readOnly;
    private ConfigurableApplicationContext applicationContext;
    private Map<String, AbstractHarvester> hmHarvesters = new HashMap<>();
    private Map<String, AbstractHarvester> hmHarvestLookup = new HashMap<>();

    public ConfigurableApplicationContext getApplicationContext() {
        return applicationContext;
    }

    //---------------------------------------------------------------------------
    //---              searchProfiles
    //--- Constructor
    //---
    //---------------------------------------------------------------------------

    /**
     * initialize the manager.
     *
     * @param context service context
     * @throws Exception hmm
     */
    @Override
    public void init(ServiceContext initContext, boolean isReadOnly) throws Exception {
        //create a new (shared) context instead of using the Jeeves one
        ServiceManager serviceManager = initContext.getBean(ServiceManager.class);
        this.harvesterContext = serviceManager.createServiceContext("harvester", initContext);

        this.dataMan = harvesterContext.getBean(DataManager.class);
        this.settingMan = harvesterContext.getBean(HarvesterSettingsManager.class);
        applicationContext = harvesterContext.getApplicationContext();

        this.readOnly = isReadOnly;
        Log.debug(Geonet.HARVEST_MAN, "HarvesterManager initializing, READONLYMODE is " + this.readOnly);
        xslPath = harvesterContext.getAppPath().resolve(Geonet.Path.STYLESHEETS).resolve("xml/harvesting/");
        AbstractHarvester.getScheduler().getListenerManager().addJobListener(
            HarversterJobListener.getInstance(this));

        final Element harvesting = settingMan.getList(null);
        if (harvesting != null) {
            Element entries = harvesting.getChild("children");

            if (entries != null) {
                for (Object o : entries.getChildren()) {
                    Element node = transform((Element) o);
                    String type = node.getAttributeValue("type");
                    String id = node.getAttributeValue("id");

                    try {
                        AbstractHarvester ah = AbstractHarvester.create(type, harvesterContext);
                        ah.init(node, harvesterContext);
                        hmHarvesters.put(ah.getID(), ah);
                        hmHarvestLookup.put(ah.getParams().getUuid(), ah);
                    } catch (OperationAbortedEx oae) {
                        Log.error(Geonet.HARVEST_MAN, "Cannot create harvester " + id + " of type \""
                            + type + "\"", oae);
                    }

                }
            }
        }
    }

    /**
     * Sorted harvester nodes.
     *
     * @param nodes     harvest nodes
     * @param sortField sort field
     * @return sorted harvest nodes
     * @throws Exception hmm
     */
    private Element transformSort(Element nodes, String sortField) throws Exception {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sortField", sortField);

        return Xml.transform(nodes, xslPath.resolve(Geonet.File.SORT_HARVESTERS), params);
    }

    /**
     * Transformed harvest node
     *
     * @param node harvest node
     * @return transformed harvest node
     * @throws Exception hmm
     */
    private Element transform(Element node) throws Exception {
        String type = node.getChildText("value");
        node = (Element) node.clone();
        return Xml.transform(node, xslPath.resolve(type + ".xsl"));
    }

    /**
     * Clean up harvest manager.
     */
    @Override
    public void shutdown() {
        for (AbstractHarvester ah : hmHarvesters.values()) {
            try {
                ah.shutdown();
            } catch (SchedulerException e) {
                Log.error(Geonet.HARVEST_MAN, "Error shutting down" + ah.getID(), e);
            }
        }
        try {
            AbstractHarvester.shutdownScheduler();
        } catch (SchedulerException e) {
            Log.error(Geonet.HARVEST_MAN, "Error shutting down harvester scheduler");
        }
        //we created the context, so we have to clean it up
        if (harvesterContext != null){
            harvesterContext.clear();
        }
    }

    //---------------------------------------------------------------------------
    //---
    //--- API methods
    //---
    //---------------------------------------------------------------------------

    /**
     * Harvest node, filtered to only include nodes visible to user session.
     *
     * @param id      harvester id
     * @param context servicecontext
     * @param sort    sort field
     * @return harvest node
     * @throws Exception hmm
     */
    @Override
    public Element get(String id, ServiceContext context, String sort) throws Exception {
        Element result = null;
        if (id == null) {
            result = settingMan.getList(null);
        } else if (id.equals("-1")) {
            // use list of names to get only necessary parameters
            result = settingMan.getList(summaryHarvesterSettings);
        } else {
            result = settingMan.get("harvesting/id:" + id, -1);
        }

        if (result == null) {
            return null;
        }

        // TODO use a parameter in mask to avoid call again in base
        // and use it for call harvesterSettingsManager.get
        // don't forget to clean parameter when update or delete

        Profile profile = context.getUserSession().getProfile();
        if (id != null && !id.equals("-1")) {
            // you're an Administrator
            if (profile == Profile.Administrator) {
                result = transform(result);
                addInfo(result);
            } else {
                // you're not an Administrator: only return harvest nodes from groups visible to you
                GeonetContext gc = (GeonetContext) context.getHandlerContext(Geonet.CONTEXT_NAME);
                AccessManager am = gc.getBean(AccessManager.class);
                Set<Integer> groups = am.getVisibleGroups(context.getUserSession().getUserIdAsInt());
                result = transform(result);
                Element nodeGroup = result.getChild("ownerGroup");
                if ((nodeGroup != null) && (groups.contains(Integer.valueOf(nodeGroup.getValue())))) {
                    addInfo(result);
                } else {
                    return null;
                }
            }
        } else {

            // id is null or -1: return all (visible) nodes
            Element nodes = (Element) result.getChild("children");
            result = new Element("nodes");
            if (nodes != null) {
                // you're Administrator: all nodes are visible
                if (profile == Profile.Administrator) {
                    for (Object o : nodes.getChildren()) {
                        Element node = transform((Element) o);
                        addInfo(node);
                        result.addContent(node);
                    }
                } else {
                    // you're not an Adminstrator: only return nodes in groups visible to you
                    GeonetContext gc = (GeonetContext) context.getHandlerContext(Geonet.CONTEXT_NAME);
                    AccessManager am = gc.getBean(AccessManager.class);
                    Set<Integer> groups = am.getVisibleGroups(context.getUserSession().getUserIdAsInt());
                    for (Object o : nodes.getChildren()) {
                        Element node = transform((Element) o);
                        Element nodeGroup = node.getChild("ownerGroup");
                        if ((nodeGroup != null)
                                && (!StringUtils.isEmpty(nodeGroup.getValue()))
                                && (StringUtils.isNumeric(nodeGroup.getValue()))
                                && (groups.contains(Integer.valueOf(nodeGroup.getValue())))) {
                            addInfo(node);
                            result.addContent(node);
                        }
                    }
                }
                // sort according to sort field
                if (sort != null) {
                    result = transformSort(result, sort);
                }
            }
        }
        return result;
    }

    /**
     * Add harvester, returning the id new harvester.
     *
     * @param node    harvester config
     * @param ownerId the id of the user doing this
     * @return id of new harvester
     * @throws JeevesException hmm
     * @throws SQLException    hmm
     */
    @Override
    public String addHarvesterReturnId(Element node, String ownerId) throws JeevesException, SQLException {
        if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
            Log.debug(Geonet.HARVEST_MAN, "Adding harvesting node : \n" + Xml.getString(node));
        }
        String type = node.getAttributeValue("type");

        AbstractHarvester ah = AbstractHarvester.create(type, harvesterContext);

        Element ownerIdE = new Element("ownerId");
        ownerIdE.setText(ownerId);
        node.addContent(ownerIdE);

        ah.add(node);
        hmHarvesters.put(ah.getID(), ah);
        hmHarvestLookup.put(ah.getParams().getUuid(), ah);

        if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
            Log.debug(Geonet.HARVEST_MAN, "Added node with id : \n" + ah.getID());
        }
        return ah.getID();
    }

    /**
     * Add harvester, returning UUID.
     * @return uuid of new harvester
     */
    @Override
    public String addHarvesterReturnUUID(Element node) throws JeevesException, SQLException {
        if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
            Log.debug(Geonet.HARVEST_MAN, "Adding harvesting node : \n" + Xml.getString(node));
        }
        String type = node.getAttributeValue("type");
        AbstractHarvester ah = AbstractHarvester.create(type, harvesterContext);

        ah.add(node);
        hmHarvesters.put(ah.getID(), ah);
        hmHarvestLookup.put(ah.getParams().getUuid(), ah);

        if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
            Log.debug(Geonet.HARVEST_MAN, "HarvestManager added node with id: " + ah.getID() + " and uuid: " + ah.getParams().getUuid());
        }
        return ah.getParams().getUuid();
    }

    /**
     * Clone harvester.
     *
     * @param ownerId id of the user doing this
     * @return id of the new harvester
     */
    @Override
    public synchronized String createClone(String id, String ownerId, ServiceContext context) throws Exception {
        // get the specified harvester from the settings table
        Element node = get(id, context, null);
        if (node == null) return null;

        // remove info from the harvester we will clone
        Element info = node.getChild("info");
        if (info != null) info.removeContent();
        Element site = node.getChild("site");
        if (site != null) {
            Element name = site.getChild("name");
            if (name != null) {
                String nameStr = name.getText();
                // TODO i18n
                name.setText("clone: " + nameStr);
            }
        }

        if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
            Log.debug(Geonet.HARVEST_MAN, "Cloning harvesting node : \n" + Xml.getString(node));
        }
        Element ownerIdE = new Element("ownerId");
        ownerIdE.setText(ownerId);
        node.addContent(ownerIdE);

        // now add a new harvester based on the settings in the old
        return addHarvesterReturnId(node, ownerId);
    }

    /**
     * Update on harvester progress.
     *
     * @param ownerId id of the user doing this
     * @return true of harvester updated
     */
    @Override
    public synchronized boolean update(Element node, String ownerId) throws BadInputEx, SQLException, SchedulerException {
        if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
            Log.debug(Geonet.HARVEST_MAN, "Updating harvesting node : \n" + Xml.getString(node));
        }
        String id = node.getAttributeValue("id");

        if (id == null) {
            throw new MissingParameterEx("attribute:id", node);
        }
        AbstractHarvester ah = hmHarvesters.get(id);

        if (ah == null) {
            return false;
        }

        Element ownerIdE = new Element("ownerId");
        ownerIdE.setText(ownerId);
        node.addContent(ownerIdE);

        ah.update(node);
        return true;
    }

    /**
     * This method must be synchronized because it cannot run if we are updating some entries.
     */
    @Override
    public synchronized OperResult remove(final String id) throws Exception {
        try {
            if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
                Log.debug(Geonet.HARVEST_MAN, "Removing harvesting with id : " + id);
            }
            if (!NumberUtils.isDigits(id)) {
                return OperResult.NOT_FOUND;
            }
            AbstractHarvester ah = hmHarvesters.get(id);
            String harvesterSetting = settingMan.getValue("harvesting/id:" + id);
            String uuid = settingMan.getValue("harvesting/id:" + id + "/site/uuid");
            if (StringUtils.isNotBlank(harvesterSetting)) {
                settingMan.remove("harvesting/id:" + id);

                final HarvestHistoryRepository historyRepository = harvesterContext.getBean(HarvestHistoryRepository.class);
                // set deleted status in harvest history table to 'y'
                historyRepository.markAllAsDeleted(uuid);
                hmHarvesters.remove(id);
                if (ah != null) {
                    ah.destroy();
                }
                return OperResult.OK;
            } else {
                return OperResult.NOT_FOUND;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Start harvester
     * @param id harvester id
     */
    @Override
    public OperResult start(String id) throws SQLException, SchedulerException {
        if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
            Log.debug(Geonet.HARVEST_MAN, "Starting harvesting with id : " + id);
        }
        AbstractHarvester ah = hmHarvesters.get(id);

        if (ah == null) {
            return OperResult.NOT_FOUND;
        }
        return ah.start();
    }

    /**
     * Stop harvester with provided status, and unschedule any outstanding jobs.
     * @param id harvester id
     * @param status New status
     * @return harvester progress
     */
    @Override
    public OperResult stop(String id, Common.Status status) throws SQLException, SchedulerException {
        if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
            Log.debug(Geonet.HARVEST_MAN, "Stopping harvesting with id : " + id);
        }
        AbstractHarvester ah = hmHarvesters.get(id);

        if (ah == null) {
            return OperResult.NOT_FOUND;
        }
        return ah.stop(status);
    }

    /**
     * Start harvester run
     * @parm id harvester to run
     * return harvester progress
     */
    @Override
    public OperResult run(String id) throws SQLException, SchedulerException {
        // READONLYMODE
        if (!this.readOnly) {
            if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
                Log.debug(Geonet.HARVEST_MAN, "Running harvesting with id: " + id);
            }
            AbstractHarvester ah = hmHarvesters.get(id);

            if (ah == null) {
                return OperResult.NOT_FOUND;
            }
            return ah.run();
        } else {
            if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
                Log.debug(Geonet.HARVEST_MAN, "GeoNetwork is running in read-only mode: skipping run of harvester with id: " + id);
            }
            return null;
        }
    }

    /**
     * Run the harvester check if harvest correctly completed.
     */
    @Override
    public OperResult invoke(String id) {
        // READONLYMODE
        if (!this.readOnly) {
            if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
                Log.debug(Geonet.HARVEST_MAN, "Invoking harvester with id: " + id);
            }

            AbstractHarvester ah = hmHarvesters.get(id);
            if (ah == null) {
                return OperResult.NOT_FOUND;
            }
            return ah.invoke();
        } else {
            if (Log.isDebugEnabled(Geonet.HARVEST_MAN)) {
                Log.debug(Geonet.HARVEST_MAN, "GeoNetwork is running in read-only mode: skipping invocation of harvester with id: " + id);
            }
            return null;
        }
    }

    /**
     * Harvester details
     * @param harvestUuid uuid to look up harvester info
     * @param id
     * @param uuid
     */
    public Element getHarvestInfo(String harvestUuid, String id, String uuid) {
        Element info = new Element(Edit.Info.Elem.HARVEST_INFO);
        AbstractHarvester ah = hmHarvestLookup.get(harvestUuid);

        if (ah != null) {
            ah.addHarvestInfo(info, id, uuid);
        }
        return info;
    }

    @Override
    public AbstractHarvester getHarvester(String harvestUuid) {
        return hmHarvestLookup.get(harvestUuid);
    }

    //---------------------------------------------------------------------------
    //---
    //--- Private methods
    //---
    //---------------------------------------------------------------------------

    /**
     *
     * @param node
     */
    private void addInfo(Element node) {
        String id = node.getAttributeValue("id");
        if (hmHarvesters.get(id) != null) {
            hmHarvesters.get(id).addInfo(node);
        } else {
            Log.warning(Geonet.HARVEST_MAN, "Trying to add info to a " +
                "non existing harvester with id : " + id);
        }
    }

    /**
     * Remove harvester information. For example, when records are removed, clean the last status
     * information if any.
     */
    public void removeInfo(String id, String ownerId) throws Exception {
        // get the specified harvester from the settings table
        Element node = get(id, harvesterContext, null);
        if (node != null) {
            Element info = node.getChild("info");
            if (info != null) {
                info.removeContent();
            }
            update(node, ownerId);
        }
    }

    /**
     *
     * @return
     */
    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     *
     * @param readOnly
     */
    @Override
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public synchronized OperResult clearBatch(String id) throws Exception {
        if (Log.isDebugEnabled(Geonet.HARVEST_MAN))
            Log.debug(Geonet.HARVEST_MAN, "Clearing harvesting with id : " + id);

        AbstractHarvester<?, ?> ah = hmHarvesters.get(id);

        if (ah == null) {
            return OperResult.NOT_FOUND;
        }

        long elapsedTime = System.currentTimeMillis();

        String harvesterUUID = ah.getParams().getUuid();

        final Specification<Metadata> specification = (Specification<Metadata>) MetadataSpecs.hasHarvesterUuid(harvesterUUID);
        int numberOfRecordsRemoved = dataMan.batchDeleteMetadataAndUpdateIndex(specification);
        ah.emptyResult();
        elapsedTime = (System.currentTimeMillis() - elapsedTime) / 1000;

        // clear last run info
        if(harvesterContext.getUserSession() != null ) {
            // This is unexpected has harvesterContext is shared between threads that may
            // by running in parallel
            removeInfo(id, harvesterContext.getUserSession().getUserId());
            ah.emptyResult();
        }

        Element historyEl = new Element("result");
        historyEl.addContent(new Element("cleared").
            setAttribute("recordsRemoved", numberOfRecordsRemoved + ""));
        final String lastRun = new DateTime().withZone(DateTimeZone.forID("UTC")).toString();
        ISODate lastRunDate = new ISODate(lastRun);

        HarvestHistoryRepository historyRepository = harvesterContext.getBean(HarvestHistoryRepository.class);
        HarvestHistory history = new HarvestHistory();
        history.setDeleted(true);
        history.setElapsedTime((int) elapsedTime);
        history.setHarvestDate(lastRunDate);
        history.setHarvesterName(ah.getParams().getName());
        history.setHarvesterType(ah.getType());
        history.setHarvesterUuid(ah.getParams().getUuid());
        history.setInfo(historyEl);
        history.setParams(ah.getParams().getNodeElement());

        historyRepository.save(history);
        return OperResult.OK;
    }

    @Override
    public void rescheduleActiveHarvesters() {
        String timeZoneSetting = applicationContext.getBean(SettingManager.class).getValue(Settings.SYSTEM_SERVER_TIMEZONE, true);
        if (StringUtils.isBlank(timeZoneSetting)) {
            timeZoneSetting = TimeZone.getDefault().getID();
        }

        for (Map.Entry<String, AbstractHarvester> pair : hmHarvesters.entrySet()) {
           AbstractHarvester harvester = pair.getValue();
           if ( Common.Status.ACTIVE.equals(harvester.getStatus())) {
               try {
                   TimeZone triggerTimeZone =  harvester.getTriggerTimezone();
                   String triggerTimeZoneId = TimeZone.getDefault().getID();
                   if (triggerTimeZone != null) {
                       triggerTimeZoneId = triggerTimeZone.getID();
                   }

                   if (!StringUtils.equals(timeZoneSetting, triggerTimeZoneId)) {
                       harvester.doReschedule();
                   }
               } catch (SchedulerException e) {
                   Log.error(Geonet.HARVEST_MAN, String.format("Error rescheduling harvester %s - '%s'", harvester.getID(),
                       harvester.getParams().getName()), e);
               }
           }
        }

    }
}
