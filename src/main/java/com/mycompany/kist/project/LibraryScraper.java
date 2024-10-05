package com.mycompany.kist.project;

import com.mycompany.kist.project.ElasticSearchConfig;
import com.mycompany.kist.project.Book;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.json.JsonData;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LibraryScraper {

    private static final String BASE_URL = "https://www.toplukatalog.gov.tr/?cwid=2";
    private static final String KEYWORD = "Ankara Tarihi";
    private static final String INDEX_NAME = "books";
    private static final int BATCH_SIZE = 100;

    private ElasticsearchClient client;

    public LibraryScraper() {
        this.client = ElasticSearchConfig.getClient();
    }

    public void run() {
        try {
            createIndexIfNotExists();
            scrapeAndIndexData();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            ElasticSearchConfig.closeClient();
        }
    }

    private void createIndexIfNotExists() throws IOException {
        boolean exists = client.indices().exists(e -> e.index(INDEX_NAME)).value();

        if (!exists) {
            CreateIndexRequest createIndexRequest = new CreateIndexRequest.Builder()
                    .index(INDEX_NAME)
                    .mappings(m -> m
                            .properties("materyalTuru", p -> p.text(t -> t))
                            .properties("baslik", p -> p.text(t -> t))
                            .properties("yazar", p -> p.text(t -> t))
                            .properties("yayinYili", p -> p.integer(i -> i))
                            .properties("basi", p -> p.text(t -> t))
                            .properties("dil", p -> p.text(t -> t))
                            .properties("konu", p -> p.text(t -> t))
                            .properties("kutuphane", p -> p.text(t -> t))
                    )
                    .build();

            client.indices().create(createIndexRequest);
            System.out.println("İndex oluşturuldu: " + INDEX_NAME);
        } else {
            System.out.println("İndex zaten mevcut: " + INDEX_NAME);
        }
    }

    private void scrapeAndIndexData() {
        int currentPage = 1;
        boolean hasNextPage = true;
        List<Book> booksBatch = new ArrayList<>();

        while (hasNextPage) {
            try {
                String url = BASE_URL + "&keyword=" + KEYWORD.replace(" ", "+") + "&tokat_search_field=1&order=0&page=" + currentPage;
                System.out.println("Fetching: " + url);
                Document doc = Jsoup.connect(url).get();

                // Tüm tabloları seç
                Elements tables = doc.select("td.result_headers_container table");

                if (tables.isEmpty()) {
                    System.out.println("No tables found on page: " + currentPage);
                    hasNextPage = false;
                    continue;
                }

                for (Element table : tables) {
                    Elements rows = table.select("tr"); // Her bir satırı seç

                    Book book = new Book();

                    for (Element row : rows) {
                        Elements headers = row.select("td.result_headers"); // Başlıkları seç
                        Elements data = row.select("td:not(.result_headers)"); // Başlığa bağlı veriyi seç

                        if (!headers.isEmpty() && !data.isEmpty()) {
                            String headerText = headers.text().trim();
                            String dataText = data.text().trim();

                            switch (headerText) {
                                case "Materyal Türü":
                                    book.setMateryalTuru(dataText);
                                    break;
                                case "Başlık":
                                    book.setBaslik(dataText);
                                    break;
                                case "Yazar":
                                    book.setYazar(dataText);
                                    break;
                                case "Yayın Yılı":
                                    try {
                                        book.setYayinYili(Integer.parseInt(dataText));
                                    } catch (NumberFormatException e) {
                                        book.setYayinYili(null);
                                    }
                                    break;
                                case "Bası":
                                    book.setBasi(dataText);
                                    break;
                                case "Dil":
                                    book.setDil(dataText);
                                    break;
                                case "Konu":
                                    book.setKonu(dataText);
                                    break;
                                case "Kütüphane":
                                    book.setKutuphane(dataText);
                                    break;
                                default:
                                    // Diğer başlıklar için eklemeler yapabilirsiniz
                                    break;
                            }
                        }
                    }

                    // Kitap nesnesini toplu indekslemeye ekle
                    booksBatch.add(book);
                    System.out.println("Kayıt:: " + booksBatch.size() + " - " + book.getBaslik());

                    // Eğer batch boyutuna ulaşıldıysa indeksle
                    if (booksBatch.size() >= BATCH_SIZE) {
                        bulkIndexBooks(booksBatch);
                        booksBatch.clear();
                    }
                }

                System.out.println("***********\n");
                currentPage++;

            } catch (IOException e) {
                e.printStackTrace();
                hasNextPage = false;  // Bir hata olursa döngüyü bitir
            }
        }

        // Döngü sonrası kalan verileri gönderin
        if (!booksBatch.isEmpty()) {
            bulkIndexBooks(booksBatch);
            booksBatch.clear();
        }

        System.out.println("Tüm sayfalar işlendi ve veriler Elasticsearch'e yüklendi.");
    }

    private void bulkIndexBooks(List<Book> books) {
        try {
            BulkRequest.Builder br = new BulkRequest.Builder();

            for (Book book : books) {
                br.operations(op -> op
                        .index(idx -> idx
                                .index(INDEX_NAME)
                                .document(book)
                        )
                );
            }

            BulkResponse bulkResponse = client.bulk(br.build());

            if (bulkResponse.errors()) {
                System.out.println("Bazı belgeler indekslenirken hata oluştu.");
                bulkResponse.items().forEach(item -> {
                    if (item.error() != null) {
                        System.out.println("Error: " + item.error().reason());
                    }
                });
            } else {
                System.out.println("Toplu indeksleme başarılı: " + books.size() + " belge.");
            }

        } catch (ElasticsearchException | IOException e) {
            e.printStackTrace();
        }
    }
}
