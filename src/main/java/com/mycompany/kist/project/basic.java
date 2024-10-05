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
    private static final String KEYWORD = "Lenovo";

    public static void main(String[] args) {
        // Elasticsearch'e bağlan
        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient client = new ElasticsearchClient(transport);

        int currentPage = 1;
        List<Book> books = new ArrayList<>();
        int totalPagesFetched = 0; // Çekilen toplam sayfa sayısını tutacak
        int count = 1;
        while (true) {
            try {
                String url = BASE_URL + "&keyword=" + KEYWORD.replace(" ", "+") + "&tokat_search_field=1&order=0&page=" + currentPage;
                System.out.println("Fetching: " + url);
                Document doc = Jsoup.connect(url).get();

                // Tüm tabloları seç
                Elements tables = doc.select("td.result_headers_container table");

                boolean pageHasBooks = false; // Sayfada kitap olup olmadığını kontrol etmek için

                for (Element table : tables) {
                    Elements rows = table.select("tr"); // Her bir satırı seç
                    System.out.println(count);
                    count++;
                    Book book = new Book();
                    for (Element row : rows) {
                        Elements headers = row.select("td.result_headers"); // Başlıkları seç
                        Elements data = row.select("td:not(.result_headers)"); // Başlığa bağlı veriyi seç
                        if (!headers.isEmpty() && !data.isEmpty()) {

                            for (int i = 0; i < headers.size(); i++) {
                                String headerText = headers.get(i).text(); // Başlık metni
                                String dataText = data.get(i).text(); // Veri metni
                                System.out.println("Header - Data text : " + headerText + " : " + dataText);
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
                                        book.setYayinYili(Integer.parseInt(dataText));
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
                            }
                            
                            pageHasBooks = true; // Sayfada en az bir kitap var
                        }
                    }
                    books.add(book); // Her kitabı listeye ekle
                    System.out.println("\n****************************\n");
                }

                // Eğer sayfada kitap yoksa veya 3 sayfa çekildiyse döngüyü kır
                if (!pageHasBooks || totalPagesFetched == 2) {
                    System.out.println("No books found on page " + currentPage + " or fetched 3 pages. Exiting.");
                    break;
                }

                // Sayfa sayısını artır
                currentPage++;
                totalPagesFetched++; // Çekilen toplam sayfa sayısını artır

            } catch (IOException e) {
                System.out.println("Connection error: " + e.getMessage());
                break; // Bağlantı hatası olursa döngüyü bitir
            } catch (Exception e) {
                e.printStackTrace();
                break; // Başka bir hata olursa döngüyü bitir
            }
        }
        System.out.println("Books size:" +books.size());

        // Verileri Elasticsearch'e gönder
        for (Book book : books) {
            IndexRequest<Book> indexRequest = IndexRequest.of(i -> i
                    .index("lenovo") // Daha önce oluşturduğunuz index            
                    .document(book) // Book nesnesini gönder
            );
            System.out.println(book.getAllData() + count + "\n*********\n");
            count++;
            try {
                IndexResponse indexResponse = client.index(indexRequest);
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
