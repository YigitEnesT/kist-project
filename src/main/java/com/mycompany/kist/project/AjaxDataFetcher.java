package com.mycompany.kist.project;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class AjaxDataFetcher {

    private static final String BASE_URL = "https://www.toplukatalog.gov.tr/?";
    private static final int TOTAL_PAGE = 5398; // Sayfa sayısı, hedef web sitesindeki ile eşit olmalı.
    private static final String KEYWORD = "2";
    private static final int[] LIBRARY_ID = {39, 40, 41, 1290}; // İstenilen kütüphanelerin idleri.
    private static final List<Integer> failedPages = new ArrayList<>();

    public static void main(String[] args) {
        OkHttpClient client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        ExecutorService executor = new ThreadPoolExecutor(
                5, 
                10, 
                60L, TimeUnit.SECONDS, 
                new ArrayBlockingQueue<>(50), 
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        RabbitMQProducer rabbitMQProducer = new RabbitMQProducer();

        try {
            for (int currentPage = 1; currentPage <= TOTAL_PAGE; currentPage++) {
                int finalCurrentPage = currentPage;
                executor.submit(() -> fetchPageData(finalCurrentPage, client, rabbitMQProducer));
                Thread.sleep(200);
            }

            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);

            if (!failedPages.isEmpty()) {
                System.out.println("Başarısız sayfalar yeniden işleniyor: " + failedPages);
                retryFailedPages(client, rabbitMQProducer);
            }

        } catch (InterruptedException e) {
            System.out.println("İşlemler kesildi: " + e.getMessage());
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                System.out.println("Executor kapanırken hata oluştu: " + e.getMessage());
            }
        }
    }

    private static void fetchPageData(int currentPage, OkHttpClient client, RabbitMQProducer rabbitMQProducer) {
        String libraries = urlWithLibraries(LIBRARY_ID);
        String url = BASE_URL + "cwid=2&keyword=" + KEYWORD + "%2A&tokat_search_field=1" + libraries + "&order=0&ts=1729280178&page=" + currentPage;

        int retryCount = 0;
        while (retryCount < 3) {
            try {
                Request request = new Request.Builder().url(url).build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        parseAndSendData(response.body().string(), rabbitMQProducer, currentPage);
                        return;
                    } else {
                        System.out.println("Request failed: " + response.code() + " on page " + currentPage);
                    }
                }
            } catch (IOException e) {
                System.out.println("Hata oluştu: " + e.getMessage() + " page: " + currentPage);
            }

            retryCount++;
            try {
                Thread.sleep((long) Math.pow(2, retryCount) * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("Sayfa çekilemedi: " + currentPage);
        failedPages.add(currentPage);
    }

    private static void parseAndSendData(String responseBody, RabbitMQProducer rabbitMQProducer, int currentPage) {
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

            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            String message = gson.toJson(books);
            sendToRabbitMQ(message, rabbitMQProducer);

        } else {
            System.out.println("Div with id 'search_results' not found on page " + currentPage);
        }
    }

    private static void sendToRabbitMQ(String message, RabbitMQProducer producer) {
        int retryCount = 0;
        while (retryCount < 3) {
            try {
                producer.sendMessage(message);
                return;
            } catch (Exception e) {
                System.out.println("RabbitMQ gönderim hatası: " + e.getMessage());
                retryCount++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        System.out.println("RabbitMQ gönderim başarısız: " + message);
    }

    private static void retryFailedPages(OkHttpClient client, RabbitMQProducer rabbitMQProducer) {
        for (int page : failedPages) {
            fetchPageData(page, client, rabbitMQProducer);
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
