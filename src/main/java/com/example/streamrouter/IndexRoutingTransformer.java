package com.example.streamrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class IndexRoutingTransformer {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String splunkHecUrl = System.getenv().getOrDefault("SPLUNK_HEC_URL", "http://localhost:8088/services/collector");
    private static final String splunkHecToken = System.getenv().getOrDefault("SPLUNK_HEC_TOKEN", "changeme");
    private static final String otelIndex = System.getenv().getOrDefault("OTEL_SPLUNK_INDEX", "default");
    private static final String inputTopic = System.getenv().getOrDefault("KAFKA_INPUT_TOPIC", "raw-otel-topic");
    private static final String updatedIndex = otelIndex + "_logs";

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "otel-index-routing-app-direct");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, String> input = builder.stream(inputTopic);

        input.foreach((key, value) -> {
            try {
                JsonNode root = objectMapper.readTree(value);
                ((ObjectNode) root).put("otel.splunkindex", updatedIndex);
                String payload = objectMapper.writeValueAsString(root);
                sendToSplunkWithRetry(payload, 3);
            } catch (Exception e) {
                System.err.println("Error processing message: " + e.getMessage());
                e.printStackTrace();
            }
        });

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }

    private static void sendToSplunkWithRetry(String payload, int maxRetries) {
        String event = "{\"event\":" + """ + payload.replace(""", "\"") + "",\"index\":\"" + updatedIndex + "\"}";
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(splunkHecUrl).openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Splunk " + splunkHecToken);
                conn.setRequestProperty("Content-Type", "application/json");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(event.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    return;
                } else {
                    System.err.println("Failed to send to Splunk. Response code: " + responseCode);
                }
            } catch (Exception e) {
                System.err.println("Error sending to Splunk (attempt " + (attempt + 1) + "): " + e.getMessage());
            }

            attempt++;
            try {
                Thread.sleep(2000); // backoff delay
            } catch (InterruptedException ignored) {}
        }

        System.err.println("Exceeded max retries. Failed to send event to Splunk.");
    }
}
