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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AjaxDataFetcher {

    private static final String BASE_URL = "https://www.toplukatalog.gov.tr/?";
    private static final int TOTAL_PAGE = 1031; // Toplam sayfa sayısı
    private static final int[] LIBRARY_ID = {1031}; // Kütüphane ID'leri

    public static void main(String[] args) {
        OkHttpClient client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true) // Bağlantı hatalarında tekrar dene
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS) // Yanıt okuma zaman aşımı
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        // Elasticsearch'e bağlan
        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient elasticsearchClient = new ElasticsearchClient(transport);

        // ExecutorService ile paralel işleme için iş parçacıkları havuzu
        ExecutorService executor = Executors.newFixedThreadPool(10);

        try {
            for (int currentPage = 1; currentPage <= TOTAL_PAGE; currentPage++) {
                int finalCurrentPage = currentPage;
                executor.submit(() -> fetchPageData(finalCurrentPage, client, elasticsearchClient));
                try {
                    Thread.sleep(200);  // İstekler arasında 200 ms gecikme
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // İşlemleri tamamlayıp iş parçacıklarını kapat
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);

        } catch (InterruptedException e) {
            System.out.println("İşlemler kesildi: " + e.getMessage());
        } finally {
            try {
                // Elasticsearch bağlantılarını kapat
                transport.close();
                restClient.close();
            } catch (IOException e) {
                System.out.println("Elasticsearch bağlantısı kapatılamadı: " + e.getMessage());
            }
        }
    }

    // Sayfa verilerini çeken ve işleyen metod
    private static void fetchPageData(int currentPage, OkHttpClient client, ElasticsearchClient elasticsearchClient) {
        String libraries = urlWithLibraries(LIBRARY_ID);
        Request request = new Request.Builder()
                .url(BASE_URL + "cwid=2&keyword=9%2A&tokat_search_field=1" + libraries + "&order=0&ts=1729280178&page=" + currentPage)
                .build();

        int maxRetries = 3; // Maksimum tekrar sayısı
        int retryCount = 0; // Şu ana kadar kaç kez denendiğini tutan sayaç
        long retryDelay = 4000; // Her başarısız denemede bekleme süresi (milisaniye cinsinden)

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
                        List<BulkOperation> operations = new ArrayList<>();
                        for (JsonElement book : books) {
                            Map<String, Object> dataMap = gson.fromJson(book, HashMap.class);
                            operations.add(BulkOperation.of(b -> b
                                    .index(i -> i
                                    .index("enes") // Elasticsearch indeks adı
                                    .document(dataMap)
                                    )));
                        }
                        if (!operations.isEmpty()) {
                            sendBulkRequest(elasticsearchClient, operations);
                        }
                    } else {
                        System.out.println("Div with id 'search_results' not found on page " + currentPage);
                    }
                    break; // Başarılı olursa döngüyü kır
                } else {
                    System.out.println("Request failed: " + response.code() + " on page " + currentPage);
                }
            } catch (IOException e) {
                System.out.println("Veri çekme sırasında hata (deneme " + (retryCount + 1) + "): " + e.getMessage());

                // Eğer maksimum deneme sayısına ulaşılmadıysa tekrar dene
                if (retryCount < maxRetries - 1) {
                    try {
                        Thread.sleep(retryDelay); // Bekleme süresi
                    } catch (InterruptedException interruptedException) {
                        System.out.println("Bekleme sırasında hata: " + interruptedException.getMessage());
                    }
                }
            }

            retryCount++; // Her başarısız denemeden sonra sayaç artırılır
        }

        if (retryCount == maxRetries) {
            System.out.println("Veri çekme işlemi başarısız oldu ve maksimum deneme sayısına ulaşıldı.");
        }
    }

    // Kütüphane ID'lerini URL'ye ekleyen metod
    private static String urlWithLibraries(int[] libIds) {
        StringBuilder urlBuilder = new StringBuilder();
        for (int id : libIds) {
            urlBuilder.append("&tokat_library%5B%5D=").append(id);
        }
        return urlBuilder.toString();
    }

    // Elasticsearch'e bulk request gönderen metod
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
            } else {
                //System.out.println(operations.size() + " kayıt başarıyla Elasticsearch'e gönderildi.");
            }
        } catch (ElasticsearchException e) {
            System.out.println("Bulk işlemi sırasında hata: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("I/O hatası: " + e.getMessage());
        }
    }
}
