package com.mycompany.kist.project;

/**
 *
 * @author yetun
 */
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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
    private static final int TOTAL_PAGE = 647;
    private static final int[] LIBRARY_ID = {105, 106, 107, 108, 1134};

    public static void main(String[] args) {
        OkHttpClient client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        ExecutorService executor = Executors.newFixedThreadPool(10);
        RabbitMQProducer rabbitMQProducer = new RabbitMQProducer();

        try {
            for (int currentPage = 1; currentPage <= TOTAL_PAGE; currentPage++) {
                int finalCurrentPage = currentPage;
                executor.submit(() -> fetchPageData(finalCurrentPage, client, rabbitMQProducer));
                Thread.sleep(200); // Kısa bir bekleme
            }

            // İşlemleri tamamlayıp iş parçacıklarını kapat
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            System.out.println("İşlemler kesildi: " + e.getMessage());
        } finally {
            executor.shutdown(); // Executor'ı kapat
            try {
                executor.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                System.out.println("Executor kapanırken hata oluştu: " + e.getMessage());
            }
        }
    }

    private static void fetchPageData(int currentPage, OkHttpClient client, RabbitMQProducer rabbitMQProducer) {
        String libraries = urlWithLibraries(LIBRARY_ID);
        Request request = new Request.Builder()
                .url(BASE_URL + "cwid=2&keyword=8%2A&tokat_search_field=1" + libraries + "&order=0&ts=1729280178&page=" + currentPage)
                .build();

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

                    // RabbitMQ'ya gönder
                    Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
                    String message = gson.toJson(books);
                    rabbitMQProducer.sendMessage(message);

                } else {
                    System.out.println("Div with id 'search_results' not found on page " + currentPage);
                }
            } else {
                System.out.println("Request failed: " + response.code() + " on page " + currentPage);
            }
        } catch (IOException e) {
            System.out.println("Veri çekme işlemi başarısız oldu: " + e.getMessage());
        }
    }

    private static String urlWithLibraries(int[] libIds) {
        StringBuilder urlBuilder = new StringBuilder();
        for (int id : libIds) {
            urlBuilder.append("&tokat_library%5B%5D=").append(id);
        }
        return urlBuilder.toString();
    }
}