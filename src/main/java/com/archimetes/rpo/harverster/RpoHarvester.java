package com.archimetes.rpo.harverster;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import picocli.CommandLine;

import javax.net.ssl.SSLContext;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@CommandLine.Command(name = "RpoHarvester", mixinStandardHelpOptions = true,
        version = "RpoHarvester 1.0.0",
        description = "Imports data from RPO and FS to Elasticsearch")
public class RpoHarvester implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Command ${COMPLETION-CANDIDATES}")
    private Command command;

    @CommandLine.Option(names = {"-e", "--elastic"}, description = "Elasticsearch url", required = true)
    private String elastic;

    @CommandLine.Option(names = {"-f", "--full"}, description = "For full RPO import. Otherwise downloads only increment")
    private boolean fullRpo;


    @CommandLine.Option(names = {"-u", "--elasticuser"}, description = "Elasticsearch user name")
    private String elasticuser;

    @CommandLine.Option(names = {"-p", "--elasticpassword"}, description = "Elasticsearch password")
    String elasticpassword;

    @CommandLine.Option(names = {"-t", "--truststore"}, description = "Elasticsearch truststore location")
    String trustStore = "/Users/rehak/rasto/archimetes/Projekty/RpoHarvester/source/RpoHarvester/RpoHarvester/.data/ca/ca.p12";

    @CommandLine.Option(names = {"-tp", "--tspassword"}, description = "Elasticsearch truststore password")
    String trustStorePassword = "";

    static final String RPO_BASE_URL = "https://frkqbrydxwdp.compat.objectstorage.eu-frankfurt-1.oraclecloud.com/susr-rpo";


    RestClient client;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RpoHarvester()).execute(args);
        System.exit(exitCode);
    }

    private void fsImport(String url, String listElement, String itemElement) {

        try (ZipInputStream zin = new ZipInputStream(org.apache.hc.client5.http.fluent.Request.get(url).execute().returnContent().asStream())) {
            ZipEntry nextEntry;
            while (zin.available() > 0) {
                nextEntry = zin.getNextEntry();
                if (nextEntry != null && nextEntry.getName().endsWith(".xml")) {
                    SAXParserFactory factory = SAXParserFactory.newInstance();
                    SAXParser saxParser = factory.newSAXParser();
                    saxParser.parse(zin, new DefaultHandler() {
                        private StringBuilder elementValue;

                        private JSONObject values;

                        int count = 0;

                        StringBuilder bulk = new StringBuilder();
                        int size = 0;

                        @Override
                        public void characters(char[] ch, int start, int length) {
                            if (elementValue == null) {
                                elementValue = new StringBuilder();
                            } else {
                                elementValue.append(ch, start, length);
                            }
                        }

                        @Override
                        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                            super.startElement(uri, localName, qName, attributes);
                            if (qName.equals(itemElement)) {
                                values = new JSONObject();
                            }
                        }

                        @Override
                        public void endElement(String uri, String localName, String qName) {

                            if (qName.equals(listElement)) {
                                if (size > 0) {
                                    postBulk(bulk);
                                }
                                System.out.println(listElement + " done " + count);
                            } else if (qName.equals(itemElement)) {
                                if (values != null && values.has("ico")) {
                                    count++;
                                    bulk.append("{ \"update\" : {\"_id\" : \"").append(values.getString("ico")).append("\", \"_index\" : \"rpo\"} }\n");
                                    bulk.append(new JSONObject().put("doc", new JSONObject().put(listElement.toLowerCase(), values)).toString()).append("\n");
                                    size++;
                                    if (size >= 5000) {
                                        postBulk(bulk);
                                        size = 0;
                                        bulk = new StringBuilder();
                                        System.out.println(listElement + " count " + count);
                                    }
                                }
                                values = null;
                            } else {
                                if (values != null && elementValue != null) {
                                    values.put(qName.toLowerCase(), elementValue.toString().trim());
                                }
                            }
                            elementValue = null;
                        }
                    });
                    break;
                }
            }

        } catch (IOException | ParserConfigurationException | SAXException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void postBulk(StringBuilder bulk) {
        Request request = new Request("POST", "/_bulk/");
        request.setJsonEntity(bulk.toString());

        try {
            client.performRequest(request).getEntity();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private void rpoImport() {
        try {
            Request request = new Request("GET", "/_cat/indices/rpo?v&format=json&ignore_unavailable=true");
            boolean rpoMissing = new JSONArray(EntityUtils.toString(client.performRequest(request).getEntity())).isEmpty();

            org.apache.hc.client5.http.fluent.Response response = org.apache.hc.client5.http.fluent.Request.get(RPO_BASE_URL).execute();
            XmlMapper xmlMapper = new XmlMapper();
            JsonNode node = xmlMapper.readTree(response.returnContent().asStream());
            JsonNode contents = node.get("Contents");
            if (fullRpo || rpoMissing) {
                System.out.println("Delete old index rpo");
                if (!rpoMissing) {
                    request = new Request("DELETE", "/rpo");
                    client.performRequest(request);
                } else {
                    System.out.println("Rpo index does not exist. Forcing full import");
                }
                request = new Request("PUT", "/rpo");
                client.performRequest(request);
                for (int i = 0; i < contents.size(); i++) {
                    JsonNode content = contents.get(i);
                    String key = content.get("Key").asText();
                    if (key.startsWith("batch-init") && key.endsWith(".json.gz")) {
                        System.out.println(key);
                        parseRpoJson(RPO_BASE_URL + "/" + key);
                    }
                }
            }
            for (int i = 0; i < contents.size(); i++) {
                JsonNode content = contents.get(i);
                String key = content.get("Key").asText();
                if (key.startsWith("batch-daily") && key.endsWith(".json.gz")) {
                    System.out.println(key);
                    parseRpoJson(RPO_BASE_URL + "/" + key);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private void parseRpoJson(String url) {
        try (GZIPInputStream zis = new GZIPInputStream(org.apache.hc.client5.http.fluent.Request.get(url).execute().returnContent().asStream())) {
            JsonFactory jsonFactory = new JsonFactory();
            jsonFactory.setCodec(new ObjectMapper());
            try (JsonParser jParser = jsonFactory
                    .createParser(zis)) {
                while (jParser.nextToken() != JsonToken.START_ARRAY) {

                }
                int total = 0;
                int failed = 0;
                StringBuilder builder = new StringBuilder();
                int size = 0;
                while (jParser.nextToken() != JsonToken.END_ARRAY) {
                    JsonNode node = jParser.readValueAsTree();
                    JsonNode identifiers = node.get("identifiers");
                    String idValue = null;
                    total++;
                    if (identifiers != null) {
                        JSONArray ids = new JSONArray(identifiers.toPrettyString());
                        if (!ids.isEmpty()) {
                            for (int i = 0; i < ids.length(); i++) {
                                JSONObject id = ids.getJSONObject(i);
                                if (!id.has("validTo")) {
                                    idValue = id.getString("value");
                                }
                            }
                        }
                    }
                    if (idValue == null) {
                        failed++;
                    } else {
                        builder.append("{ \"index\" : { \"_index\" : \"rpo\", \"_id\" : \"").append(idValue).append("\" } }\n");
                        builder.append(new JSONObject().put("rpo", new JSONObject(node.toString())).toString());
                        builder.append("\n");
                        size++;
                    }

                    if (size >= 1000) {
                        postBulkItems(builder);
                        builder = new StringBuilder();
                        size = 0;
                    }
                }
                if (size > 0) {
                    postBulkItems(builder);
                }
                System.out.println("Total:" + total + " noId:" + failed);

            }


        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void postBulkItems(StringBuilder builder) throws IOException {
        Request request = new Request("POST", "/_bulk/");
        request.setJsonEntity(builder.toString());
        Response response = client.performRequest(request);
        JSONObject result = new JSONObject(EntityUtils.toString(response.getEntity()));
        if (result.getBoolean("errors")) {
            System.out.println(result);
        } else {
            System.out.println("Saved items " + result.getJSONArray("items").length());
        }
    }

    @Override
    public Integer call() throws Exception {
        URL url = new URL(elastic);
        HttpHost httpHost = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());

        SSLContext sslContext;
        if ("https".equalsIgnoreCase(url.getProtocol())) {
            try {
                if (trustStore != null && !trustStore.isEmpty()) {
                    KeyStore sslTrustStore = KeyStore.getInstance("PKCS12");
                    if (new File(trustStore).isFile()) {
                        try (InputStream inputStream = new FileInputStream(trustStore)) {
                            sslTrustStore.load(inputStream, trustStorePassword == null ? null : trustStorePassword.toCharArray());
                        }
                    }
                    sslContext = SSLContexts.custom().loadTrustMaterial(sslTrustStore, null).build();
                } else sslContext = SSLContext.getDefault();
            } catch (Exception ex) {
                throw new RuntimeException("Could not create SSL context", ex);
            }
        } else sslContext = null;

        RestClientBuilder builder = RestClient.builder(httpHost);

        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            if (sslContext != null) {
                httpClientBuilder.setSSLContext(sslContext);
            }
            if (elasticuser != null) {
                final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(elasticuser, elasticpassword));

                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }
            return httpClientBuilder;
        });


        client = builder.build();
        client.performRequest(new Request("GET", "/"));

        System.out.println(command);
        switch (command) {
            case dph -> fsImport("https://report.financnasprava.sk/ds_dphs.zip", "DS_DPHS", "ITEM");
            case rdp -> fsImport("https://report.financnasprava.sk/ds_dsrdp.zip", "DS_DSRDP", "ITEM");
            case ran -> fsImport("https://report.financnasprava.sk/ds_iz_ran.zip", "DS_IZ_RAN", "ITEM");
            case rpo -> rpoImport();
            case reindex -> reindex();
            case all -> importAll();
        }
        return 0;
    }


    private void importAll() {
        rpoImport();
        fsImport("https://report.financnasprava.sk/ds_dphs.zip", "DS_DPHS", "ITEM");
        fsImport("https://report.financnasprava.sk/ds_dsrdp.zip", "DS_DSRDP", "ITEM");
        fsImport("https://report.financnasprava.sk/ds_iz_ran.zip", "DS_IZ_RAN", "ITEM");
        reindex();
    }

    private void reindex() {
        try {
            Request request = new Request("PUT", "/_index_template/rpo_actual");
            request.setJsonEntity(new String(getClass().getResourceAsStream("/_index_template/rpo_actual.json").readAllBytes(), StandardCharsets.UTF_8));
            Response response = client.performRequest(request);
            if (response.getStatusLine().getStatusCode() != 200) {
                System.err.println("Error create index template rpo_actual \n" + EntityUtils.toString(response.getEntity()));
            } else {
                System.out.println("Index template rpo_actual created");
            }
            request = new Request("PUT", "/_ingest/pipeline/rpo_actual");
            request.setJsonEntity(new String(getClass().getResourceAsStream("/_ingest/pipeline/rpo_actual.json").readAllBytes(), StandardCharsets.UTF_8));
            response = client.performRequest(request);
            if (response.getStatusLine().getStatusCode() != 200) {
                System.err.println("Error create ingest pipeline rpo_actual \n" + EntityUtils.toString(response.getEntity()));
            } else {
                System.out.println("Ingest pipeline rpo_actual created");
            }

            request = new Request("PUT", "/_scripts/rpo_autocomplete");
            request.setJsonEntity(new String(getClass().getResourceAsStream("/_scripts/rpo_autocomplete.json").readAllBytes(), StandardCharsets.UTF_8));
            response = client.performRequest(request);
            if (response.getStatusLine().getStatusCode() != 200) {
                System.err.println("Error create script rpo_autocomplete \n" + EntityUtils.toString(response.getEntity()));
            } else {
                System.out.println("Script rpo_autocomplete created");
            }

            int nextIndexSuffix = 1;
            request = new Request("GET", "/_cat/indices/rpo_actual-*?v&format=json");
            response = client.performRequest(request);

            List<String> oldNames = new ArrayList<>();
            if (response.getStatusLine().getStatusCode() == 200) {
                JSONArray oldIndices = new JSONArray(EntityUtils.toString(response.getEntity()));
                for (int i = 0; i < oldIndices.length(); i++) {
                    oldNames.add(oldIndices.getJSONObject(i).getString("index"));
                }
                while (oldNames.contains("rpo_actual-" + nextIndexSuffix)) {
                    nextIndexSuffix++;
                }
            }


            String indexName = "rpo_actual-" + nextIndexSuffix;
            System.out.println("Create index " + indexName);
            request = new Request("PUT", "/" + indexName);
            client.performRequest(request);

            request = new Request("POST", "/_reindex?wait_for_completion=false");
            request.setJsonEntity(new JSONObject().put("conflicts", "proceed").put("source", new JSONObject().put("index", "rpo")).put("dest", new JSONObject().put("index", indexName)).toString());
            response = client.performRequest(request);

            if (response.getStatusLine().getStatusCode() == 200) {
                String task = new JSONObject(EntityUtils.toString(response.getEntity())).getString("task");
                System.out.println("Reindex task " + task);
                Thread.sleep(1000);
                while (true) {
                    request = new Request("GET", "/_tasks/" + task);
                    response = client.performRequest(request);
                    if (response.getStatusLine().getStatusCode() == 200) {
                        JSONObject taskStatus = new JSONObject(EntityUtils.toString(response.getEntity()));
                        boolean completed = taskStatus.getBoolean("completed");
                        if (taskStatus.getJSONObject("task").getJSONObject("status").getLong("total") > 0) {
                            System.out.println("Reindex " + (100L * taskStatus.getJSONObject("task").getJSONObject("status").getLong("created") / taskStatus.getJSONObject("task").getJSONObject("status").getLong("total")) + "%\r");
                        }
                        if (completed) {
                            break;
                        }
                        Thread.sleep(1000);
                    } else {
                        break;
                    }
                }
                System.out.println("Done");
            }

            request = new Request("POST", "/_aliases");
            request.setJsonEntity(new JSONObject().put("actions", new JSONArray().put(new JSONObject().put("add", new JSONObject().put("index", indexName).put("alias", "rpo_actual")))).toString());
            client.performRequest(request);
            for (String name : oldNames) {
                System.out.println("Delete old index " + name);
                request = new Request("DELETE", "/" + name);
                client.performRequest(request);
            }

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }


    }
}


enum Command {
    rpo,
    rdp,
    dph,
    ran,
    reindex,
    all

}