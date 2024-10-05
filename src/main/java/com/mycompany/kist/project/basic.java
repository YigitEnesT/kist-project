/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.kist.project;

/**
 *
 * @author yetun
 */
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

public class basic {

    private static final String BASE_URL = "https://www.toplukatalog.gov.tr/?cwid=2";
    private static final String KEYWORD = "Ankara Tarihi";
    private static final int MAX_PAGES = 512; // Toplam sayfa sayısı

    public static void main(String[] args) {
        // Elasticsearch'e bağlan
        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient client = new ElasticsearchClient(transport);

        int currentPage = 1;
        List<Book> books = new ArrayList<>();

        while (currentPage <= MAX_PAGES) {
            try {
                String url = BASE_URL + "&keyword=" + KEYWORD.replace(" ", "+") + "&tokat_search_field=1&order=0&page=" + currentPage;
                System.out.println("Fetching: " + url);
                Document doc = Jsoup.connect(url).get();

                // Tüm tabloları seç
                Elements tables = doc.select("td.result_headers_container table");

                boolean pageHasBooks = false; // Sayfada kitap olup olmadığını kontrol etmek için

                for (Element table : tables) {
                    Elements rows = table.select("tr"); // Her bir satırı seç

                    for (Element row : rows) {
                        Elements headers = row.select("td.result_headers"); // Başlıkları seç
                        Elements data = row.select("td:not(.result_headers)"); // Başlığa bağlı veriyi seç

                        if (!headers.isEmpty() && !data.isEmpty()) {
                            Book book = new Book(); // Yeni Book nesnesi oluştur
                            for (int i = 0; i < headers.size(); i++) {
                                String headerText = headers.get(i).text(); // Başlık metni
                                String dataText = data.get(i).text(); // Veri metni

                                // Book nesnesini doldur
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
                                        book.setYayinYili(Integer.parseInt(dataText));
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
                                }
                            }
                            books.add(book); // Book nesnesini listeye ekle
                            pageHasBooks = true; // Sayfada en az bir kitap var
                        }
                    }
                }

                // Eğer sayfada hiç kitap yoksa, daha fazla sayfa kalmadığı anlamına gelebilir
                if (!pageHasBooks) {
                    break; // Sayfada kitap yoksa döngüyü kır
                }

                currentPage++; // Sonraki sayfaya geç
            } catch (Exception e) {
                e.printStackTrace();
                break;  // Bir hata olursa döngüyü bitir
            }
        }

        // Verileri Elasticsearch'e gönder
        for (Book book : books) {
            IndexRequest<Book> indexRequest = IndexRequest.of(i -> i
                    .index("books") // Daha önce oluşturduğunuz index            
                    .document(book) // Book nesnesini gönder
            );

            try {
                IndexResponse indexResponse = client.index(indexRequest);
                System.out.println("Indexed document with id: " + indexResponse.id());
            } catch (Exception e) {
                System.out.println("Hata oluştu: " + e.getMessage());
            }
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
}
