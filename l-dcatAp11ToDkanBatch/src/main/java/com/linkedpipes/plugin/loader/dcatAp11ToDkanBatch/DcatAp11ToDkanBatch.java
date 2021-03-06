package com.linkedpipes.plugin.loader.dcatAp11ToDkanBatch;

import com.linkedpipes.etl.dataunit.core.rdf.SingleGraphDataUnit;
import com.linkedpipes.etl.executor.api.v1.LpException;
import com.linkedpipes.etl.executor.api.v1.component.Component;
import com.linkedpipes.etl.executor.api.v1.component.SequentialExecution;
import com.linkedpipes.etl.executor.api.v1.service.ExceptionFactory;
import com.linkedpipes.etl.executor.api.v1.service.ProgressReport;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;

import org.apache.http.NameValuePair;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.FOAF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class DcatAp11ToDkanBatch implements Component, SequentialExecution {

    private static final Logger LOG = LoggerFactory.getLogger(DcatAp11ToDkanBatch.class);

    @Component.InputPort(iri = "Metadata")
    public SingleGraphDataUnit metadata;

    @Component.InputPort(iri = "Codelists")
    public SingleGraphDataUnit codelists;

    @Component.Configuration
    public DcatAp11ToDkanBatchConfiguration configuration;

    @Component.Inject
    public ExceptionFactory exceptionFactory;

    @Component.Inject
    public ProgressReport progressReport;

    private CloseableHttpClient queryClient = HttpClientBuilder.create().setRedirectStrategy(new LaxRedirectStrategy()).build();
    private CloseableHttpClient createClient = HttpClientBuilder.create().setRedirectStrategy(new LaxRedirectStrategy()).build();
    private CloseableHttpClient postClient = HttpClients.createDefault();

    private String apiURI;

    private static final int pagesize = 20;

    private String fixKeyword(String keyword) {
        return keyword.replace(",","")
                .replace(".","")
                .replace("/","-")
                .replace(":","-")
                .replace(";","-")
                .replace("§", "paragraf");
    }

    private Map<String, String> getGroups() {
        CloseableHttpResponse queryResponse = null;
        List<String> groupList = new LinkedList<>();
        Map<String, String> groups = new HashMap<>();

        LOG.debug("Querying DKAN for groups");
        int page = 0;

        while (true) {
            HttpGet httpGetOrg = new HttpGet(apiURI + "/node.json?parameters[type]=group&pagesize=" + pagesize + "&page=" + page);
            try {
                queryResponse = queryClient.execute(httpGetOrg);
                if (queryResponse.getStatusLine().getStatusCode() == 200) {
                    JSONArray response = new JSONArray(EntityUtils.toString(queryResponse.getEntity()));
                    if (response.length() == 0) {
                        LOG.info("No more groups found");
                        break;
                    }
                    else {
                        for (Object o : response) {
                            JSONObject jo = (JSONObject) o;
                            groupList.add(jo.getString("nid"));
                        }
                        LOG.info("Groups downloaded, found " + response.length() + " new groups, " + groupList.size() + "total.");
                        page++;
                    }
                } else {
                    String ent = EntityUtils.toString(queryResponse.getEntity());
                    LOG.info("Groups not downloaded: " + ent);
                }
            } catch (Exception e) {
                LOG.error(e.getLocalizedMessage(), e);
            } finally {
                if (queryResponse != null) {
                    try {
                        queryResponse.close();
                    } catch (IOException e) {
                        LOG.error(e.getLocalizedMessage(), e);
                    }
                }
            }
        }

        LOG.debug("Querying for group details.");

        for (String group : groupList) {
            HttpGet httpGetOrgDetail = new HttpGet(apiURI + "/node/" + group + ".json");
            try {
                queryResponse = queryClient.execute(httpGetOrgDetail);
                if (queryResponse.getStatusLine().getStatusCode() == 200) {
                    LOG.info("Group " + group + " downloaded");
                    JSONObject response = new JSONObject(EntityUtils.toString(queryResponse.getEntity()));
                    try {
                        groups.put(response.getJSONObject("body").getJSONArray("und").getJSONObject(0).getString("value"), group);
                    }
                    catch (JSONException e) { }
                } else {
                    String ent = EntityUtils.toString(queryResponse.getEntity());
                    LOG.info("Group " + group + " not downloaded: " + ent);
                }
            } catch (Exception e) {
                LOG.error(e.getLocalizedMessage(), e);
            } finally {
                if (queryResponse != null) {
                    try {
                        queryResponse.close();
                    } catch (IOException e) {
                        LOG.error(e.getLocalizedMessage(), e);
                    }
                }
            }
        }

        return groups;
    }

    private String token;

    private String getToken(String username, String password) throws LpException {
        HttpPost httpPost = new HttpPost(apiURI + "/user/login");

        ArrayList<NameValuePair> postParameters = new ArrayList<>();
        postParameters.add(new BasicNameValuePair("username", username));
        postParameters.add(new BasicNameValuePair("password", password));

        httpPost.addHeader(new BasicHeader("Accept", "application/json"));

        try {
            httpPost.setEntity(new UrlEncodedFormEntity(postParameters));
        } catch (UnsupportedEncodingException e) {
            LOG.error("Unexpected encoding issue");
        }

        CloseableHttpResponse response = null;
        String token = null;
        try {
            response = postClient.execute(httpPost);
            if (response.getStatusLine().getStatusCode() == 200) {
                LOG.debug("Logged in");
                token = new JSONObject(EntityUtils.toString(response.getEntity())).getString("token");
            } else {
                String ent = EntityUtils.toString(response.getEntity());
                LOG.error("Response:" + ent);
                throw exceptionFactory.failure("Error logging in: " + ent);
            }
        } catch (Exception e) {
            LOG.error(e.getLocalizedMessage(), e);
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    LOG.error(e.getLocalizedMessage(), e);
                    throw exceptionFactory.failure("Error logging in");
                }
            }
        }

        return token;
    }

    @Override
    public void execute() throws LpException {

        apiURI = configuration.getApiUri();

        //for HTTP request failing on "failed to respond"
        boolean responded = false;

        if (apiURI == null
                || apiURI.isEmpty()
                || configuration.getUsername() == null
                || configuration.getUsername().isEmpty()
                || configuration.getPassword() == null
                || configuration.getPassword().isEmpty()
                ) {
            throw exceptionFactory.failure("Missing required settings.");
        }

        Map<String, String> groups = getGroups();

        LOG.debug("Querying metadata for datasets");

        LinkedList<String> datasets = new LinkedList<>();
        for (Map<String,Value> map: executeSelectQuery("SELECT ?d WHERE {?d a <" + DcatAp11ToDkanBatchVocabulary.DCAT_DATASET_CLASS + ">}")) {
            datasets.add(map.get("d").stringValue());
        }

        int current = 0;
        int total = datasets.size();
        LOG.info("Found " + total + " datasets");
        progressReport.start(total);

        token = getToken(configuration.getUsername(), configuration.getPassword());

        for (String datasetURI : datasets) {
            current++;

            CloseableHttpResponse queryResponse = null;

            LOG.info("Processing dataset " + current + "/" + total + ": " + datasetURI);

            String publisher_uri = executeSimpleSelectQuery("SELECT ?publisher_uri WHERE {<" + datasetURI + "> <" + DCTERMS.PUBLISHER + "> ?publisher_uri }", "publisher_uri");
            String publisher_name = executeSimpleSelectQuery("SELECT ?publisher_name WHERE {<" + datasetURI + "> <" + DCTERMS.PUBLISHER + ">/<" + FOAF.NAME + "> ?publisher_name FILTER(LANGMATCHES(LANG(?publisher_name), \"" + configuration.getLoadLanguage() + "\"))}", "publisher_name");

            if (!groups.containsKey(publisher_uri)) {
                LOG.debug("Creating group " + publisher_uri);

                if (publisher_name == null || publisher_name.isEmpty()) {
                    throw exceptionFactory.failure("Publisher has no name: " + publisher_uri);
                }

                HttpPost httpPost = new HttpPost(apiURI + "/node");
                httpPost.addHeader(new BasicHeader("Accept", "application/json"));
                httpPost.addHeader(new BasicHeader("X-CSRF-Token", token));

                ArrayList<NameValuePair> postParameters = new ArrayList<>();
                postParameters.add(new BasicNameValuePair("type", "group"));
                postParameters.add(new BasicNameValuePair("title", publisher_name));
                postParameters.add(new BasicNameValuePair("body[und][0][value]", publisher_uri));

                try {
                    UrlEncodedFormEntity form = new UrlEncodedFormEntity(postParameters, "UTF-8");
                    httpPost.setEntity(form);
                } catch (UnsupportedEncodingException e) {
                    LOG.error("Unexpected encoding issue");
                }

                CloseableHttpResponse response = null;

                responded = false;
                do {
                    try {
                        response = postClient.execute(httpPost);
                        if (response.getStatusLine().getStatusCode() == 200) {
                            LOG.debug("Group created OK");
                            String orgID = new JSONObject(EntityUtils.toString(response.getEntity())).getString("nid");
                            groups.put(publisher_uri, orgID);
                        } else {
                            String ent = EntityUtils.toString(response.getEntity());
                            LOG.error("Group:" + ent);
                            //throw exceptionFactory.failed("Error creating group: " + ent);
                        }
                        responded = true;
                    } catch (Exception e) {
                        LOG.error(e.getLocalizedMessage(), e);
                    } finally {
                        if (response != null) {
                            try {
                                response.close();
                            } catch (IOException e) {
                                LOG.error(e.getLocalizedMessage(), e);
                                throw exceptionFactory.failure("Error creating group");
                            }
                        }
                    }
                } while (!responded);
            }

            ArrayList<NameValuePair> datasetFields = new ArrayList<>();
            datasetFields.add(new BasicNameValuePair("type", "dataset"));

            LinkedList<String> keywords = new LinkedList<>();
            for (Map<String, Value> map : executeSelectQuery("SELECT ?keyword WHERE {<" + datasetURI + "> <" + DcatAp11ToDkanBatchVocabulary.DCAT_KEYWORD + "> ?keyword FILTER(LANGMATCHES(LANG(?keyword), \"" + configuration.getLoadLanguage() + "\"))}")) {
                keywords.add(map.get("keyword").stringValue());
            }
            String concatTags = "";
            for (String keyword : keywords) {
                String safekeyword = fixKeyword(keyword);
                if (safekeyword.length() >= 2) {
                    concatTags += "\"\"" + safekeyword + "\"\" ";
                }
            }
            if (!concatTags.isEmpty()) {
                datasetFields.add(new BasicNameValuePair("field_tags[und][value_field]", concatTags));
            }

            String title = executeSimpleSelectQuery("SELECT ?title WHERE {<" + datasetURI + "> <" + DCTERMS.TITLE + "> ?title FILTER(LANGMATCHES(LANG(?title), \"" + configuration.getLoadLanguage() + "\"))}", "title");
            if (!title.isEmpty()) {
                datasetFields.add(new BasicNameValuePair("title", title));
            }
            String description = executeSimpleSelectQuery("SELECT ?description WHERE {<" + datasetURI + "> <" + DCTERMS.DESCRIPTION + "> ?description FILTER(LANGMATCHES(LANG(?description), \"" + configuration.getLoadLanguage() + "\"))}", "description");
            if (!description.isEmpty()) {
                datasetFields.add(new BasicNameValuePair("body[und][0][value]", description));
            } else if (configuration.getProfile().equals(DcatAp11ToDkanBatchVocabulary.PROFILES_NKOD.stringValue())) {
                //Description is mandatory in NKOD. If missing, add at least title.
                datasetFields.add(new BasicNameValuePair("body[und][0][value]", title));
            }
            String issued = executeSimpleSelectQuery("SELECT ?issued WHERE {<" + datasetURI + "> <" + DCTERMS.ISSUED + "> ?issued }", "issued");
            if (!issued.isEmpty()) {
                //long unixTime = System.currentTimeMillis() / 1000L;
                datasetFields.add(new BasicNameValuePair("created", issued));
            }
            String modified = executeSimpleSelectQuery("SELECT ?modified WHERE {<" + datasetURI + "> <" + DCTERMS.MODIFIED + "> ?modified }", "modified");
            if (!modified.isEmpty()) {
                datasetFields.add(new BasicNameValuePair("changed", modified));
            }

            if (!publisher_uri.isEmpty()) {
                datasetFields.add(new BasicNameValuePair("og_group_ref[und][target_id]", groups.get(publisher_uri)));
            }

            if (configuration.getProfile().equals(DcatAp11ToDkanBatchVocabulary.PROFILES_NKOD.stringValue())) {
                String contactPoint = executeSimpleSelectQuery("SELECT ?contact WHERE {<" + datasetURI + "> <" + DcatAp11ToDkanBatchVocabulary.DCAT_CONTACT_POINT + ">/<" + DcatAp11ToDkanBatchVocabulary.VCARD_HAS_EMAIL + "> ?contact }", "contact");
                if (!contactPoint.isEmpty()) {
                    datasetFields.add(new BasicNameValuePair("field_maintainer_email[und][0][value]", contactPoint));
                }
                String curatorName = executeSimpleSelectQuery("SELECT ?name WHERE {<" + datasetURI + "> <" + DcatAp11ToDkanBatchVocabulary.DCAT_CONTACT_POINT + ">/<" + DcatAp11ToDkanBatchVocabulary.VCARD_FN + "> ?name }", "name");
                if (!curatorName.isEmpty()) {
                    datasetFields.add(new BasicNameValuePair("field_maintainer[und][0][value]", curatorName));
                }
                if (!publisher_uri.isEmpty()) {
                    datasetFields.add(new BasicNameValuePair("field_publisher_uri[und][0][value]", publisher_uri));
                }
                if (!publisher_name.isEmpty()) {
                    datasetFields.add(new BasicNameValuePair("field_publisher_name[und][0][value]", publisher_name));
                }

                String periodicity = executeSimpleSelectQuery("SELECT ?periodicity WHERE {<" + datasetURI + "> <"+ DCTERMS.ACCRUAL_PERIODICITY + "> ?periodicity }", "periodicity");
                if (!periodicity.isEmpty()) {
                    datasetFields.add(new BasicNameValuePair("field_frequency_ods[und][0][value]", periodicity));
                } else {
                    //Mandatory in NKOD
                    datasetFields.add(new BasicNameValuePair("field_frequency_ods[und][0][value]", "http://publications.europa.eu/resource/authority/frequency/UNKNOWN"));
                }
                String temporalStart = executeSimpleSelectQuery("SELECT ?temporalStart WHERE {<" + datasetURI + "> <"+ DCTERMS.TEMPORAL + ">/<" + DcatAp11ToDkanBatchVocabulary.SCHEMA_STARTDATE + "> ?temporalStart }", "temporalStart");
                if (!temporalStart.isEmpty()) {
                    datasetFields.add(new BasicNameValuePair("field_temporal_start[und][0][value]", temporalStart));
                }
                String temporalEnd = executeSimpleSelectQuery("SELECT ?temporalEnd WHERE {<" + datasetURI + "> <"+ DCTERMS.TEMPORAL + ">/<" + DcatAp11ToDkanBatchVocabulary.SCHEMA_ENDDATE  + "> ?temporalEnd }", "temporalEnd");
                if (!temporalEnd.isEmpty()) {
                    datasetFields.add(new BasicNameValuePair("field_temporal_end[und][0][value]", temporalEnd));
                }
                String schemaURL = executeSimpleSelectQuery("SELECT ?schema WHERE {<" + datasetURI + "> <"+ FOAF.PAGE + "> ?schema }", "schema");
                if (!schemaURL.isEmpty()) {
                    datasetFields.add(new BasicNameValuePair("field_schema[und][0][value]", schemaURL));
                }
                String spatial = executeSimpleSelectQuery("SELECT ?spatial WHERE {<" + datasetURI + "> <"+ DCTERMS.SPATIAL + "> ?spatial }", "spatial");
                if (!spatial.isEmpty()) {
                    datasetFields.add(new BasicNameValuePair("field_spatial[und][0][value]", spatial));
                    if (spatial.matches("http:\\/\\/ruian.linked.opendata.cz\\/resource\\/.*")) {
                        String type = spatial.replaceAll("http:\\/\\/ruian.linked.opendata.cz\\/resource\\/([^\\/]+)\\/(.*)","$1");
                        String code = spatial.replaceAll("http:\\/\\/ruian.linked.opendata.cz\\/resource\\/([^\\/]+)\\/(.*)","$2");
                        String typ;
                        //We should not parse IRIs, however, here we have no choice.
                        switch (type) {
                            case "vusc":
                                typ = "VC";
                                break;
                            case "obce":
                                typ = "OB";
                                break;
                            case "kraje":
                                typ = "KR";
                                break;
                            case "orp":
                                typ = "OP";
                                break;
                            case "momc":
                                typ = "MC";
                                break;
                            case "pou":
                                typ = "PU";
                                break;
                            default:
                                typ = "ST";
                        }
                        datasetFields.add(new BasicNameValuePair("field_ruian_type[und][0][value]", typ));
                        datasetFields.add(new BasicNameValuePair("field_ruian_code[und][0][value]", code));
                    } else {
                        //RÚIAN type and code are mandatory in NKOD
                        datasetFields.add(new BasicNameValuePair("field_ruian_type[und][0][value]", "ST"));
                        datasetFields.add(new BasicNameValuePair("field_ruian_code[und][0][value]", "1"));
                    }
                }
                else {
                    //RÚIAN type and code are mandatory in NKOD
                    datasetFields.add(new BasicNameValuePair("field_ruian_type[und][0][value]", "ST"));
                    datasetFields.add(new BasicNameValuePair("field_ruian_code[und][0][value]", "1"));
                }
                //DCAT-AP v1.1: has to be an IRI from http://publications.europa.eu/mdr/authority/file-type/index.html
                LinkedList<String> themes = new LinkedList<>();
                for (Map<String,Value> map: executeSelectQuery("SELECT ?theme WHERE {<" + datasetURI + "> <"+ DcatAp11ToDkanBatchVocabulary.DCAT_THEME + "> ?theme }")) {
                    themes.add(map.get("theme").stringValue());
                }
                String concatThemes = "";
                for (String theme: themes) { concatThemes += theme + " ";}
                if (!concatThemes.isEmpty())  datasetFields.add(new BasicNameValuePair("field_theme[und][0][value]", concatThemes));
            }

            //Distributions

            LinkedList<String> distributions = new LinkedList<>();
            for (Map<String, Value> map : executeSelectQuery("SELECT ?distribution WHERE {<" + datasetURI + "> <" + DcatAp11ToDkanBatchVocabulary.DCAT_DISTRIBUTION + "> ?distribution }")) {
                distributions.add(map.get("distribution").stringValue());
            }

            for (int d = 0; d < distributions.size(); d++) {
                String distribution = distributions.get(d);
                ArrayList<NameValuePair> distroFields = new ArrayList<>();
                distroFields.add(new BasicNameValuePair("type", "resource"));

                String dtitle = executeSimpleSelectQuery("SELECT ?title WHERE {<" + distribution + "> <" + DCTERMS.TITLE + "> ?title FILTER(LANGMATCHES(LANG(?title), \"" + configuration.getLoadLanguage() + "\"))}", "title");
                if (dtitle.isEmpty()) {
                    //Distribution title is mandatory in DKAN
                    dtitle = title.isEmpty() ? "Resource" : title;
                }
                distroFields.add(new BasicNameValuePair("title", dtitle));

                String ddescription = executeSimpleSelectQuery("SELECT ?description WHERE {<" + distribution + "> <" + DCTERMS.DESCRIPTION + "> ?description FILTER(LANGMATCHES(LANG(?description), \"" + configuration.getLoadLanguage() + "\"))}", "description");
                if (!ddescription.isEmpty()) {
                    distroFields.add(new BasicNameValuePair("body[und][0][value]", ddescription));
                }
                /*String dformat = executeSimpleSelectQuery("SELECT ?format WHERE {<" + distribution + "> <"+ DCTERMS.FORMAT + "> ?format }", "format");
                if (!dformat.isEmpty() && codelists != null) {
                    String formatlabel = executeSimpleCodelistSelectQuery("SELECT ?formatlabel WHERE {<" + dformat + "> <"+ SKOS.PREF_LABEL + "> ?formatlabel FILTER(LANGMATCHES(LANG(?formatlabel), \"en\"))}", "formatlabel");
                    if (!formatlabel.isEmpty()) {
                        distroFields.add(new BasicNameValuePair("field_format[und][0][value]", formatlabel));
                    }
                }*/
                String dmimetype = executeSimpleSelectQuery("SELECT ?format WHERE {<" + distribution + "> <"+ DcatAp11ToDkanBatchVocabulary.DCAT_MEDIATYPE + "> ?format }", "format");
                if (!dmimetype.isEmpty()) {
                    distroFields.add(new BasicNameValuePair("field_link_remote_file[und][0][filemime]", dmimetype.replaceAll(".*\\/([^\\/]+\\/[^\\/]+)","$1")));
                }

                String dwnld = executeSimpleSelectQuery("SELECT ?dwnld WHERE {<" + distribution + "> <" + DcatAp11ToDkanBatchVocabulary.DCAT_DOWNLOADURL + "> ?dwnld }", "dwnld");
                String access = executeSimpleSelectQuery("SELECT ?acc WHERE {<" + distribution + "> <" + DcatAp11ToDkanBatchVocabulary.DCAT_ACCESSURL + "> ?acc }", "acc");

                //we prefer downloadURL, but only accessURL is mandatory
                if (dwnld == null || dwnld.isEmpty()) {
                    dwnld = access;
                    if (dwnld == null || dwnld.isEmpty()) {
                        LOG.warn("Empty download and access URLs: " + datasetURI);
                        continue;
                    }
                }

                if (!dwnld.isEmpty()) {
                    distroFields.add(new BasicNameValuePair("field_link_remote_file[und][0][filefield_remotefile][url]", dwnld));
                }

                /*if (!distribution.isEmpty()) {
                    distro.put("distro_url", distribution);
                }*/

                String dissued = executeSimpleSelectQuery("SELECT ?issued WHERE {<" + distribution + "> <" + DCTERMS.ISSUED + "> ?issued }", "issued");
                if (!dissued.isEmpty()) {
                    distroFields.add(new BasicNameValuePair("created", dissued));
                }
                String dmodified = executeSimpleSelectQuery("SELECT ?modified WHERE {<" + distribution + "> <" + DCTERMS.MODIFIED + "> ?modified }", "modified");
                if (!dmodified.isEmpty()) {
                    distroFields.add(new BasicNameValuePair("changed", dmodified));
                }

                if (configuration.getProfile().equals(DcatAp11ToDkanBatchVocabulary.PROFILES_NKOD.stringValue())) {
                    String dtemporalStart = executeSimpleSelectQuery("SELECT ?temporalStart WHERE {<" + distribution + "> <"+ DCTERMS.TEMPORAL + ">/<" + DcatAp11ToDkanBatchVocabulary.SCHEMA_STARTDATE + "> ?temporalStart }", "temporalStart");
                    if (!dtemporalStart.isEmpty()) {
                        distroFields.add(new BasicNameValuePair("field_temporal_start[und][0][value]", dtemporalStart));
                    }
                    String dtemporalEnd = executeSimpleSelectQuery("SELECT ?temporalEnd WHERE {<" + distribution + "> <"+ DCTERMS.TEMPORAL + ">/<" + DcatAp11ToDkanBatchVocabulary.SCHEMA_ENDDATE  + "> ?temporalEnd }", "temporalEnd");
                    if (!dtemporalEnd.isEmpty()) {
                        distroFields.add(new BasicNameValuePair("field_temporal_end[und][0][value]", dtemporalEnd));
                    }
                    String dschemaURL = executeSimpleSelectQuery("SELECT ?schema WHERE {<" + distribution + "> <"+ DCTERMS.CONFORMS_TO + "> ?schema }", "schema");
                    if (!dschemaURL.isEmpty()) {
                        distroFields.add(new BasicNameValuePair("field_described_by[und][0][value]", dschemaURL));
                    }
                    String dlicense = executeSimpleSelectQuery("SELECT ?license WHERE {<" + distribution + "> <"+ DCTERMS.LICENSE + "> ?license }", "license");
                    if (dlicense.isEmpty()) {
                        //This is mandatory in NKOD and DKAN extension
                        dlicense = "http://joinup.ec.europa.eu/category/licence/unknown-licence";
                    }
                    distroFields.add(new BasicNameValuePair("field_licence[und][0][value]", dlicense));
                    if (dmimetype.isEmpty()) {
                        //! field_format => mimetype
                        //This is mandatory in NKOD and DKAN extension
                        dmimetype = "http://www.iana.org/assignments/media-types/application/octet-stream";
                    }
                    distroFields.add(new BasicNameValuePair("field_mimetype[und][0][value]", dmimetype.replaceAll(".*\\/([^\\/]+\\/[^\\/]+)","$1")));
                }

                //POST DISTRIBUTION

                LOG.debug("Creating resource " + distribution);

                HttpPost httpPost = new HttpPost(apiURI + "/node");
                httpPost.addHeader(new BasicHeader("Accept", "application/json"));
                httpPost.addHeader(new BasicHeader("X-CSRF-Token", token));

                try {
                    UrlEncodedFormEntity form = new UrlEncodedFormEntity(distroFields, "UTF-8");
                    httpPost.setEntity(form);
                } catch (UnsupportedEncodingException e) {
                    LOG.error("Unexpected encoding issue");
                }

                CloseableHttpResponse response = null;

                String resID = null;
                responded = false;
                do {
                    try {
                        LOG.debug("POSTing resource " + distribution);
                        response = postClient.execute(httpPost);
                        if (response.getStatusLine().getStatusCode() == 200) {
                            String resp = EntityUtils.toString(response.getEntity());
                            LOG.debug("Resource created OK: " + resp);
                            try {
                                resID = new JSONObject(resp).getString("nid");
                                datasetFields.add(new BasicNameValuePair("field_resources[und][" + d + "][target_id]", dtitle + " (" + resID + ")"));
                            } catch (JSONException e) {
                                LOG.error(e.getLocalizedMessage(), e);
                                LOG.error("Request: " + distroFields.toString());
                                LOG.error("Response: " + resp);
                            }
                        } else {
                            String ent = EntityUtils.toString(response.getEntity());
                            LOG.error("Resource:" + ent);
                            //throw exceptionFactory.failed("Error creating resource: " + ent);
                        }
                        responded = true;
                    } catch (NoHttpResponseException e) {
                        LOG.error(e.getLocalizedMessage(), e);
                    } catch (IOException e) {
                        LOG.error(e.getLocalizedMessage(), e);
                    } finally {
                        if (response != null) {
                            try {
                                response.close();
                            } catch (IOException e) {
                                LOG.error(e.getLocalizedMessage(), e);
                                //throw exceptionFactory.failed("Error creating resource");
                            }
                        }
                    }
                } while (!responded) ;

            }

            LOG.debug("Creating dataset " + datasetURI);

            HttpPost httpPost = new HttpPost(apiURI + "/node");
            httpPost.addHeader(new BasicHeader("Accept", "application/json"));
            httpPost.addHeader(new BasicHeader("X-CSRF-Token", token));

            try {
                UrlEncodedFormEntity form = new UrlEncodedFormEntity(datasetFields, "UTF-8");
                httpPost.setEntity(form);
            } catch (UnsupportedEncodingException e) {
                LOG.error("Unexpected encoding issue");
            }

            CloseableHttpResponse response = null;

            responded = false ;
            do {
                try {
                    LOG.debug("POSTing dataset " + datasetURI);
                    response = postClient.execute(httpPost);
                    if (response.getStatusLine().getStatusCode() == 200) {
                        LOG.debug("Dataset created OK");
                    } else {
                        String ent = EntityUtils.toString(response.getEntity());
                        LOG.error("Dataset:" + ent);
                        //throw exceptionFactory.failed("Error creating dataset: " + ent);
                    }
                    responded = true;
                } catch (NoHttpResponseException e) {
                    LOG.error(e.getLocalizedMessage(), e);
                } catch (IOException e) {
                    LOG.error(e.getLocalizedMessage(), e);
                } finally {
                    if (response != null) {
                        try {
                            response.close();
                        } catch (IOException e) {
                            LOG.error(e.getLocalizedMessage(), e);
                            throw exceptionFactory.failure("Error creating dataset");
                        }
                    }
                }
            } while (!responded);

            progressReport.entryProcessed();
        }

        try {
            queryClient.close();
            createClient.close();
            postClient.close();
        } catch (IOException e) {
            LOG.error(e.getLocalizedMessage(), e);
        }

        progressReport.done();

    }

    private String executeSimpleSelectQuery(final String queryAsString, String bindingName) throws LpException {
        return metadata.execute((connection) -> {
            final TupleQuery preparedQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, queryAsString);
            final SimpleDataset dataset = new SimpleDataset();
            dataset.addDefaultGraph(metadata.getReadGraph());
            preparedQuery.setDataset(dataset);
            //
            final BindingSet binding = QueryResults.singleResult(preparedQuery.evaluate());
            if (binding == null) {
                return "";
            } else {
                return binding.getValue(bindingName).stringValue();
            }
        });
    }

    private List<Map<String, Value>> executeSelectQuery(final String queryAsString) throws LpException {
        return metadata.execute((connection) -> {
            final List<Map<String, Value>> output = new LinkedList<>();
            final TupleQuery preparedQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, queryAsString);
            final SimpleDataset dataset = new SimpleDataset();
            dataset.addDefaultGraph(metadata.getReadGraph());
            preparedQuery.setDataset(dataset);
            //
            TupleQueryResult result = preparedQuery.evaluate();
            while (result.hasNext()) {
                final BindingSet binding = result.next();
                final Map<String, Value> row = new HashMap<>();
                binding.forEach((item) -> {
                    row.put(item.getName(), item.getValue());
                });
                output.add(row);
            }
            return output;
        });
    }

}
