package com.mycompany.kist.project;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class WebScraper {

    private static final String BASE_URL = "https://www.toplukatalog.gov.tr/?";
    private static final int TOTAL_PAGES = 6;

    public static void main(String[] args) throws IOException {
        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient client = new ElasticsearchClient(transport);

        try {
            for (int currentPage = 1; currentPage <= TOTAL_PAGES; currentPage++) {
                String url = BASE_URL + "cwid=2&keyword=1%2A&tokat_search_field=1&tokat_library%5b%5d=55&order=0&ts=1729280178&page=" + currentPage + "&jx=1" ;
                System.out.println("URL: " + url);
                Document doc = fetchDocument(url);
                if (doc != null) {
                    List<JsonObject> booksBatch = extractBooksFromDocument(doc);
                    bulkIndexBooks(client, booksBatch);
                }
            }
        } finally {
            try {
                transport.close();
                restClient.close();
            } catch (IOException e) {
                System.out.println("Error closing resources: " + e.getMessage());
            }
        }

        System.out.println("All pages processed and data uploaded to Elasticsearch.");
    }

    private static Document fetchDocument(String url) throws IOException {
        return Jsoup.connect(url).timeout(10000).get();
    }

    private static List<JsonObject> extractBooksFromDocument(Document doc) {
        List<JsonObject> booksBatch = new ArrayList<>();
        Elements tables = doc.select("td.result_headers_container table");

        for (Element table : tables) {
            Elements rows = table.select("tr");
            JsonObject bookJson = new JsonObject();
            for (Element row : rows) {
                Elements headers = row.select("td.result_headers");
                Elements data = row.select("td:not(.result_headers)");
                if (!headers.isEmpty() && !data.isEmpty()) {
                    for (int i = 0; i < headers.size(); i++) {
                        String headerText = normalizeHeader(headers.get(i).text());
                        String dataText = data.get(i).text();
                        bookJson.addProperty(headerText, dataText);
                    }
                }
            }
            System.out.println(bookJson + "\n****\n");
            booksBatch.add(bookJson);
        }
        return booksBatch;
    }

    private static void bulkIndexBooks(ElasticsearchClient client, List<JsonObject> booksBatch) {
        if (booksBatch.isEmpty()) {
            return;
        }

        BulkRequest.Builder br = new BulkRequest.Builder();
        for (JsonObject bookJson : booksBatch) {
            BulkOperation operation = new BulkOperation.Builder()
                    .index(idx -> idx.index("avrupa").document(bookJson))
                    .build();
            br.operations(operation);
        }

        try {
            BulkResponse result = client.bulk(br.build());
            if (result.errors()) {
                System.out.println("Bulk indexing had errors");
            } else {
                System.out.println("Successfully indexed " + booksBatch.size() + " books.");
            }
        } catch (Exception e) {
            System.out.println("Bulk indexing failed: " + e.getMessage());
        }
    }

    private static String normalizeHeader(String header) {
        return Normalizer.normalize(header, Normalizer.Form.NFD)
                .replaceAll("[^\\p{ASCII}]", "")
                .replace(" ", "_")
                .toLowerCase();
    }
}
