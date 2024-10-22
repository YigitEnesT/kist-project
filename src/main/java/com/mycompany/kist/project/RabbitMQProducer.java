package com.mycompany.kist.project;

/**
 *
 * @author yetun
 */
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Channel;

public class RabbitMQProducer {

    private final static String QUEUE_NAME = "library_data_queue";
    public void sendMessage(String message) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        try (Connection connection = factory.newConnection();
                Channel channel = connection.createChannel()) {

            channel.queueDeclare(QUEUE_NAME, true, false, false, null);
            channel.basicPublish("", QUEUE_NAME, null, message.getBytes("UTF-8"));
            //System.out.println(" [x] Sent message to RabbitMQ: " + message);

        } catch (Exception e) {
            System.out.println("RabbitMQ mesajı gönderme hatası: " + e.getMessage());
        }
    }
}
