package com.example.streamrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;

import java.util.Properties;

public class IndexRoutingTransformer {

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "otel-index-routing-app");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        ObjectMapper objectMapper = new ObjectMapper();

        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, String> input = builder.stream("raw-otel-topic");

        KStream<String, String> transformed = input.mapValues(value -> {
            try {
                JsonNode root = objectMapper.readTree(value);
                if (root.has("otel.splunkindex")) {
                    String indexValue = root.get("otel.splunkindex").asText();
                    ((ObjectNode) root).put("otel.splunkindex", indexValue + "_logs");
                }
                return objectMapper.writeValueAsString(root);
            } catch (Exception e) {
                e.printStackTrace();
                return value;
            }
        });

        transformed.to("otel-transformed-topic");

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();

        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }
}
