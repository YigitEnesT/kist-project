package com.mycompany.kist.project;

/**
 *
 * @author yetun
 */
import com.rabbitmq.client.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Channel;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import java.util.List;
import java.util.Map;

public class RabbitMQConsumer {

    private final static String QUEUE_NAME = "library_data_queue";
    private final ElasticsearchClient elasticsearchClient;
    int count = 0;

    public RabbitMQConsumer() {
        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        this.elasticsearchClient = new ElasticsearchClient(transport);
    }

    public void consumeData() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("localhost");
            try (Connection connection = factory.newConnection();
                    Channel channel = connection.createChannel()) {

                channel.queueDeclare(QUEUE_NAME, true, false, false, null); // true = durable
                System.out.println("Waiting for messages...");

                DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                    String message = new String(delivery.getBody(), "UTF-8");
                    //System.out.println("Received Message: " + message + "\n*** Count : " + count + "***\n");
                    count++;
                    processBooks(message); // Mesajı işle
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false); // Mesajı onayla
                };

                // Mesajları dinlemeye başla
                channel.basicConsume(QUEUE_NAME, false, deliverCallback, consumerTag -> {
                });

                while (true) {
                    Thread.sleep(5000); // Her 5 saniyede bir kontrol et
                    if (channel.messageCount(QUEUE_NAME) == 0) {
                        System.out.println("All messages processed. Total processed: " + count);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processBooks(String message) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            List<Map<String, Object>> books = objectMapper.readValue(message, List.class);

            // Her bir kitabı ayrı ayrı Elasticsearch'e indexle
            for (Map<String, Object> book : books) {
                IndexRequest<Map<String, Object>> indexRequest = IndexRequest.of(i -> i
                        .index("firat-9") // İndex ismini belirtiyoruz
                        .document(book) // JSON verisini ekliyoruz
                );

                // Elasticsearch'e index isteğini gönder
                elasticsearchClient.index(indexRequest);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        RabbitMQConsumer consumer = new RabbitMQConsumer();
        consumer.consumeData(); // Tüketici başlat
    }
}
