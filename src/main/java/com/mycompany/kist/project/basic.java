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
import com.google.gson.JsonObject;
import java.time.Duration;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.TimeoutException; // Eklenen import

public class basic {

    private static final String BASE_URL = "https://www.toplukatalog.gov.tr/?";
    private static final List<String> KEYWORDS = Arrays.asList("1*", "2*", "3*", "4*", "5*", "6*", "7*", "8*");
    private static final int TOTAL_PAGES = 21;
    private static final int[] LIBRARY_ID = {52, 53, 54, 55}; //857
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 2;
    private static final int BATCH_SIZE = 500; // Elasticsearch'e gönderilecek batch boyutu
    private static final int WEBDRIVER_POOL_SIZE = THREAD_COUNT;
    private static WebDriverPool webDriverPool;

    private static WebDriver createWebDriver() {
        System.setProperty("webdriver.chrome.driver", "C://chromedriver-win64/chromedriver.exe");
        ChromeOptions options = new ChromeOptions();
        options.addArguments("headless");
        options.addArguments("disable-gpu"); // Optional: Improve headless performance
        options.addArguments("no-sandbox");
        options.addArguments("blink-settings=imagesEnabled=false");

        return new ChromeDriver(options);
    }

    private static Document fetchDocumentWithSelenium(String url) throws IOException {
        WebDriver driver = null;
        try {
            driver = webDriverPool.borrowDriver();
            driver.get(url);
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("td.result_headers_container")));

            String pageSource = driver.getPageSource();
            return Jsoup.parse(pageSource);
        } catch (TimeoutException e) {
            // Beklenen element bulunamadı, null döndür
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while borrowing WebDriver", e);
        } finally {
            if (driver != null) {
                webDriverPool.returnDriver(driver);
            }
        }
    }

    private static Document fetchDocumentWithRetries(String url, int maxRetries, int delay) throws IOException, InterruptedException {
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                Document doc = fetchDocumentWithSelenium(url); // Selenium ile belgeyi çekin
                if (doc == null) {
                    // Tablo bulunamadı, yeniden denemeyin
                    return null;
                }
                return doc;
            } catch (IOException e) {
                attempt++;
                if (attempt >= maxRetries) {
                    throw e;
                }
                System.out.println("Retrying (" + attempt + "/" + maxRetries + ") for URL: " + url);
                Thread.sleep(delay);
            }
        }
        throw new IOException("Failed to fetch document after " + maxRetries + " attempts");
    }

    public static void main(String[] args) {

        webDriverPool = new WebDriverPool(WEBDRIVER_POOL_SIZE);

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

        for (String keyword : KEYWORDS) {
            // Veri çekme görevlerini submit et
            for (int currentPage = 6434; currentPage <= TOTAL_PAGES; currentPage++) {
                int page = currentPage;
                count++;
                executor.submit(() -> {
                    // Lambda ifadesinin dışındaki keyword'a erişim sağlamak için final yap
                    String finalKeyword = keyword;
                    try {
                        String libraries = urlWithLibraries(LIBRARY_ID);
                        String url = BASE_URL + libraries + "&keyword=" + finalKeyword.replace(" ", "+") + "&tokat_search_field=1&order=0&page=" + page;
                        Document doc = fetchDocumentWithRetries(url, 3, 5000); // 3 deneme, her deneme arasında 5 saniye bekleme

                        // Tüm tabloları seç
                        Elements tables = doc.select("td.result_headers_container table");
                        JsonObject bookJson = new JsonObject();
                        for (Element table : tables) {
                            Elements rows = table.select("tr"); // Her bir satırı seç
                            boolean hasData = false;
                            
                            for (Element row : rows) {
                                
                                Elements headers = row.select("td.result_headers"); // Başlıkları seç
                                Elements data = row.select("td:not(.result_headers)"); // Başlığa bağlı veriyi seç
                                
                                
                                if (!headers.isEmpty() && !data.isEmpty()) {
                                    for (int i = 0; i < headers.size(); i++) {
                                        String headerText = headers.get(i).text(); // Başlık metni
                                        String dataText = data.get(i).text(); // Veri metni
                                        // Book nesnesini doldur
                                        //bookJson.put(headerText, dataText);
//                                        switch (headerText) {
//                                            case "Materyal Türü:":
//                                                book.setMateryalTuru(dataText);
//                                                break;
//                                            case "Başlık:":
//                                                book.setBaslik(dataText);
//                                                break;
//                                            case "Yazar:":
//                                                book.setYazar(dataText);
//                                                break;
//                                            case "Yayın Yılı:":
//                                                book.setYayinYili(dataText);
//                                                break;
//                                            case "Bası:":
//                                                book.setBasi(dataText);
//                                                break;
//                                            case "Dil:":
//                                                book.setDil(dataText);
//                                                break;
//                                            case "Konu:":
//                                                book.setKonu(dataText);
//                                                break;
//                                            case "Kütüphane:":
//                                                book.setKutuphane(dataText);
//                                                break;
//                                        }
                                        hasData = true;
                                    }
                                }
                            }
                            //queue.put(bookJson);

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
        }

        System.out.println(count);
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

        // WebDriver havuzunu kapat
        webDriverPool.shutdown();

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
                    .index("bilgi") // İndeks adı
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

    private static String urlWithLibraries(int[] libIds) {
        StringBuilder urlBuilder = new StringBuilder();
        for (int id : libIds) {
            urlBuilder.append("&tokat_library%5B%5D=").append(id);
        }
        return urlBuilder.toString();
    }
}
