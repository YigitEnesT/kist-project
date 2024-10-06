package com.mycompany.kist.project;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class basic {

    private static final String BASE_URL = "https://www.toplukatalog.gov.tr/?cwid=2";
    private static final String KEYWORD = "7*";
    private static final int TOTAL_PAGES = 18955;
    private static final int THREAD_COUNT = 10; // İş parçacığı sayısı
    private static final int BATCH_SIZE = 5000; // Elasticsearch'e gönderilecek batch boyutu

    public static void main(String[] args) {
        // Elasticsearch'e bağlan
        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient client = new ElasticsearchClient(transport);

        // BlockingQueue oluştur (Üretici-Tüketici modeli için)
        BlockingQueue<Book> queue = new LinkedBlockingQueue<>(BATCH_SIZE * 2); // Kuyruk kapasitesini ayarlayın

        // ExecutorService oluştur
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        int count = 0;
        // Indeksleme işlemi için ayrı bir iş parçacığı
        Thread indexingThread = new Thread(() -> {
            List<Book> booksBatch = new ArrayList<>(BATCH_SIZE);
            try {
                while (true) {
                    Book book = queue.poll(5, TimeUnit.SECONDS);
                    if (book != null) {
                        booksBatch.add(book);
                        if (booksBatch.size() >= BATCH_SIZE) {
                            bulkIndexBooks(client, booksBatch);
                            booksBatch.clear();
                        }
                    } else {
                        // Kuyruk boş, işlemi sonlandır
                        if (Thread.currentThread().isInterrupted()) {
                            break;
                        }
                        // Eğer bekleme süresi dolduysa ve batch'te veri varsa gönder
                        if (!booksBatch.isEmpty()) {
                            bulkIndexBooks(client, booksBatch);
                            booksBatch.clear();
                        }
                    }
                }
            } catch (InterruptedException e) {
                // Thread interrupted, son batch'i gönder
                if (!booksBatch.isEmpty()) {
                    bulkIndexBooks(client, booksBatch);
                }
                Thread.currentThread().interrupt();
            }
        });

        indexingThread.start();

        // Veri çekme görevlerini submit et
        for (int currentPage = 1; currentPage <= TOTAL_PAGES; currentPage++) {
            int page = currentPage;
            count++;
            executor.submit(() -> {
                try {
                    String url = BASE_URL + "&keyword=" + KEYWORD.replace(" ", "+") + "&tokat_search_field=1&order=0&page=" + page;
                    Document doc = Jsoup.connect(url)
                            .timeout(10000) // 10 saniye
                            .userAgent("Mozilla/5.0") // User-Agent belirlemek
                            .get();

                    // Tüm tabloları seç
                    Elements tables = doc.select("td.result_headers_container table");

                    for (Element table : tables) {
                        Elements rows = table.select("tr"); // Her bir satırı seç
                        Book book = new Book();
                        boolean hasData = false;

                        for (Element row : rows) {
                            Elements headers = row.select("td.result_headers"); // Başlıkları seç
                            Elements data = row.select("td:not(.result_headers)"); // Başlığa bağlı veriyi seç
                            if (!headers.isEmpty() && !data.isEmpty()) {
                                for (int i = 0; i < headers.size(); i++) {
                                    String headerText = headers.get(i).text(); // Başlık metni
                                    String dataText = data.get(i).text(); // Veri metni

                                    // Book nesnesini doldur
                                    switch (headerText) {
                                        case "Materyal Türü:":
                                            book.setMateryalTuru(dataText);
                                            break;
                                        case "Başlık:":
                                            book.setBaslik(dataText);
                                            break;
                                        case "Yazar:":
                                            book.setYazar(dataText);
                                            break;
                                        case "Yayın Yılı:":
                                            book.setYayinYili(dataText);
                                            break;
                                        case "Bası:":
                                            book.setBasi(dataText);
                                            break;
                                        case "Dil:":
                                            book.setDil(dataText);
                                            break;
                                        case "Konu:":
                                            book.setKonu(dataText);
                                            break;
                                        case "Kütüphane:":
                                            book.setKutuphane(dataText);
                                            break;
                                    }
                                    hasData = true;
                                }
                            }
                        }

                        if (hasData && !book.getBaslik().isEmpty()) {
                            // Kitabı kuyruğa ekle
                            queue.put(book);
                        }
                    }

                } catch (IOException e) {
                    System.out.println("Connection error on page " + page + ": " + e.getMessage());
                } catch (InterruptedException e) {
                    System.out.println("Thread interrupted while processing page " + page);
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.out.println("Unexpected error on page " + page + ": " + e.getMessage());
                }
            });
        }
        System.out.println("Page: " + count);
        // ExecutorService'i kapat ve tüm görevlerin tamamlanmasını bekle
        executor.shutdown();
        try {
            if (!executor.awaitTermination(24, TimeUnit.HOURS)) { // Gerekirse süreyi artırın
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }

        // Indeksleme iş parçacığını durdur
        indexingThread.interrupt();
        try {
            indexingThread.join();
        } catch (InterruptedException e) {
            System.out.println("Indexing thread interrupted during join.");
            Thread.currentThread().interrupt();
        }

        // Bağlantıları kapat
        try {
            transport.close();
            restClient.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("Tüm sayfalar işlendi ve veriler Elasticsearch'e yüklendi.");
    }

    /**
     * Elasticsearch'e toplu veri indeksler.
     *
     * @param client ElasticsearchClient örneği
     * @param booksBatch İndekslenecek Book nesnelerinin listesi
     */
    private static void bulkIndexBooks(ElasticsearchClient client, List<Book> booksBatch) {
        if (booksBatch.isEmpty()) {
            return;
        }

        BulkRequest.Builder br = new BulkRequest.Builder();
        for (Book book : booksBatch) {
            BulkOperation operation = new BulkOperation.Builder()
                    .index(idx -> idx
                    .index("books") // İndeks adı
                    .document(book)
                    )
                    .build();
            br.operations(operation);
        }

        try {
            BulkResponse result = client.bulk(br.build());

            if (result.errors()) {
                System.out.println("Bulk indexing had errors");
                result.items().forEach(item -> {
                    if (item.error() != null) {
                        System.out.println("Error indexing document ID " + item.id() + ": " + item.error().reason());
                    }
                });
            } else {
                System.out.println("Successfully indexed a batch of " + booksBatch.size() + " books.");
            }
        } catch (Exception e) {
            System.out.println("Bulk indexing failed: " + e.getMessage());
        }
    }
}
