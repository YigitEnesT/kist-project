/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.kist.project;

/**
 *
 * @author yetun
 */
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.util.HashMap;
import java.util.Map;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AjaxDataFetcher {

    private static final String BASE_URL = "https://www.toplukatalog.gov.tr/?";
    private static final int TOTAL_PAGE = 138;
    private static final int[] LIBRARY_ID = {841, 1307};
    private static final int BATCH_SIZE = 100; // Toplu olarak göndereceğimiz kayıt sayısı

    public static void main(String[] args) {
        OkHttpClient client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient elasticsearchClient = new ElasticsearchClient(transport);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<BulkOperation> batchOperations = Collections.synchronizedList(new ArrayList<>()); // Toplu işlemler için liste

        try {
            for (int currentPage = 1; currentPage <= TOTAL_PAGE; currentPage++) {
                int finalCurrentPage = currentPage;
                executor.submit(() -> fetchPageData(finalCurrentPage, client, elasticsearchClient, batchOperations));
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // İşlemleri tamamlayıp iş parçacıklarını kapat
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);

            // Kalan işlemleri topluca gönder
            if (!batchOperations.isEmpty()) {
                sendBulkRequest(elasticsearchClient, batchOperations);
            }

        } catch (InterruptedException e) {
            System.out.println("İşlemler kesildi: " + e.getMessage());
        } finally {
            try {
                transport.close();
                restClient.close();
            } catch (IOException e) {
                System.out.println("Elasticsearch bağlantısı kapatılamadı: " + e.getMessage());
            }
        }
    }

    private static void fetchPageData(int currentPage, OkHttpClient client, ElasticsearchClient elasticsearchClient, List<BulkOperation> batchOperations) {
        String libraries = urlWithLibraries(LIBRARY_ID);
        Request request = new Request.Builder()
                .url(BASE_URL + "cwid=2&keyword=8%2A&tokat_search_field=1" + libraries + "&order=0&ts=1729280178&page=" + currentPage)
                .build();

        int maxRetries = 4;
        int retryCount = 0;
        long retryDelay = 4000;

        while (retryCount < maxRetries) {
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    Document doc = Jsoup.parse(responseBody);
                    Element searchResultsDiv = doc.select("div#search_results").first();

                    if (searchResultsDiv != null) {
                        JsonArray books = new JsonArray();
                        Elements recordContainers = searchResultsDiv.select("td.result_headers_container");

                        for (Element recordContainer : recordContainers) {
                            JsonObject data = new JsonObject();
                            Elements rows = recordContainer.select("tr");
                            for (Element row : rows) {
                                Elements headers = row.select("td.result_headers");
                                Elements values = row.select("td:not(.result_headers)");
                                if (!headers.isEmpty() && !values.isEmpty()) {
                                    String header = headers.first().text().replace(":", "").trim();
                                    String value = values.first().text().trim();
                                    data.addProperty(header, value);
                                }
                            }
                            books.add(data);
                        }

                        Gson gson = new GsonBuilder()
                                .setPrettyPrinting()
                                .disableHtmlEscaping()
                                .create();
                        synchronized (batchOperations) {
                            for (JsonElement book : books) {
                                Map<String, Object> dataMap = gson.fromJson(book, HashMap.class);
                                batchOperations.add(BulkOperation.of(b -> b
                                        .index(i -> i
                                        .index("balikesir")
                                        .document(dataMap))));
                            }

                            // Eğer batch büyüklüğü sınırı aşıldıysa Elasticsearch'e gönder
                            if (batchOperations.size() >= BATCH_SIZE) {
                                sendBulkRequest(elasticsearchClient, new ArrayList<>(batchOperations));
                                batchOperations.clear(); // Gönderilen batch'i temizle
                            }
                        }
                    } else {
                        System.out.println("Div with id 'search_results' not found on page " + currentPage);
                    }
                    break;
                } else {
                    System.out.println("Request failed: " + response.code() + " on page " + currentPage);
                }
            } catch (IOException e) {
                if (retryCount < maxRetries - 1) {
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            retryCount++;
        }

        if (retryCount == maxRetries) {
            System.out.println("Veri çekme işlemi başarısız oldu ve maksimum deneme sayısına ulaşıldı - Sayfa: " + currentPage);
        }
    }

    private static String urlWithLibraries(int[] libIds) {
        StringBuilder urlBuilder = new StringBuilder();
        for (int id : libIds) {
            urlBuilder.append("&tokat_library%5B%5D=").append(id);
        }
        return urlBuilder.toString();
    }

    private static void sendBulkRequest(ElasticsearchClient client, List<BulkOperation> operations) {
        try {
            BulkRequest bulkRequest = BulkRequest.of(b -> b.operations(operations));
            BulkResponse bulkResponse = client.bulk(bulkRequest);

            if (bulkResponse.errors()) {
                System.out.println("Bulk isteğinde hatalar oluştu.");
                bulkResponse.items().forEach(item -> {
                    if (item.error() != null) {
                        System.out.println("Hata: " + item.error());
                    }
                });
            }
        } catch (ElasticsearchException e) {
            System.out.println("Bulk işlemi sırasında hata: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("I/O hatası: " + e.getMessage());
        }
    }
}
