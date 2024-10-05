package com.mycompany.kist.project;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import javax.net.ssl.SSLContext;
import org.elasticsearch.client.RestClientBuilder;
import java.io.IOException;
import org.apache.http.ssl.SSLContexts;

public class ElasticSearchConfig {

    private static final String ELASTICSEARCH_HOST = "localhost";
    private static final int ELASTICSEARCH_PORT = 9200;
    private static final String ELASTICSEARCH_SCHEME = "https";

    private static ElasticsearchClient client;

    public static ElasticsearchClient getClient() {
        if (client == null) {
            RestClientBuilder builder = RestClient.builder(new HttpHost(ELASTICSEARCH_HOST, ELASTICSEARCH_PORT, ELASTICSEARCH_SCHEME))
                .setHttpClientConfigCallback(httpClientBuilder -> {
                    // SSL sertifikası doğrulamasını bypass et
                    SSLContext sslContext = SSLContexts.createDefault();
                    return httpClientBuilder.setSSLContext(sslContext);
                });

            client = new ElasticsearchClient(new RestClientTransport(builder.build(), new JacksonJsonpMapper()));
        }
        return client;
    }

    public static void closeClient() {
        if (client != null) {
            try {
                client._transport().close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
