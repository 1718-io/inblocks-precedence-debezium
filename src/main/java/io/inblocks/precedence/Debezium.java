package io.inblocks.precedence;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

public class Debezium {

    private static final Logger LOGGER = LoggerFactory.getLogger(Debezium.class);
    private static final long REFRESH_TOPICS_PERIOD = 1000 * 60;
    private static final String NAME_SUFFIX = "Key";

    private static KafkaStreams streams = null;

    public static void main(String[] args) {
        String api = System.getenv("PRECEDENCE_API");
        String applicationId = System.getenv("PRECEDENCE_APPLICATION_ID");
        String bootstrapServers = System.getenv("PRECEDENCE_BOOTSTRAP_SERVERS");
        String inputTopicRegex = System.getenv("PRECEDENCE_INPUT_TOPIC_PATTERN");
        boolean store = "true".equals(System.getenv("PRECEDENCE_STORE"));
        if (bootstrapServers == null || applicationId == null || inputTopicRegex == null || api == null) {
            System.err.println("The following environment variables must be defined:\n" +
                    "PRECEDENCE_API\n" +
                    "PRECEDENCE_APPLICATION_ID\n" +
                    "PRECEDENCE_BOOTSTRAP_SERVERS\n" +
                    "PRECEDENCE_INPUT_TOPIC_PATTERN\n");
            System.exit(1);
        }

        LOGGER.info("Environment variables:\n" +
                "\tPRECEDENCE_API: " + api + "\n" +
                "\tPRECEDENCE_APPLICATION_ID: " + applicationId + "\n" +
                "\tPRECEDENCE_BOOTSTRAP_SERVERS: " + bootstrapServers + "\n" +
                "\tPRECEDENCE_INPUT_TOPIC_PATTERN: " + inputTopicRegex + "\n" +
                "\tPRECEDENCE_STORE: " + store + "\n");

        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        Timer timer = new Timer();
        timer.schedule(new TimerTask() {

            Pattern inputTopicPattern = Pattern.compile(inputTopicRegex);
            AdminClient client = KafkaAdminClient.create(properties);
            Set<String> topics = new HashSet<>();

            @Override
            public void run() {
                try {
                    client.listTopics().names().get().stream()
                            .filter(topic -> !topics.contains(topic) && inputTopicPattern.matcher(topic).matches())
                            .forEach(topic -> {
                                topics.add(topic);
                                if (streams != null) {
                                    streams.close();
                                    streams = null;
                                }
                            });
                } catch (InterruptedException e) {
                    return;
                } catch (ExecutionException e) {
                    System.exit(1);
                }
                if (streams != null || topics.size() == 0) {
                    return;
                }
                Topology topology = buildTopology(topics, api, store);
                streams = new KafkaStreams(topology, properties);
                try {
                    streams.start();
                } catch (Throwable e) {
                    System.exit(1);
                }
            }
        }, 0, REFRESH_TOPICS_PERIOD);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            timer.cancel();
            if (streams != null) {
                streams.close();
            }
            Runtime.getRuntime().halt(0);
        }));
    }

    private static Topology buildTopology(Collection<String> topics, String api, boolean store) {
        StreamsBuilder streamsBuilder = new StreamsBuilder();
        streamsBuilder.stream(topics).filter((key, value) -> value != null).foreach((key, value) -> {
            // key
            JsonObject keyJson = JsonParser.parseString(key.toString()).getAsJsonObject();
            String name = keyJson.getAsJsonObject("schema").get("name").getAsString();
            JsonObject payload = keyJson.getAsJsonObject("payload");
            JsonObject sortedPayload = new JsonObject();
            payload.keySet().stream().sorted().forEach(s -> sortedPayload.add(s, payload.get(s)));
            if (!name.endsWith(NAME_SUFFIX)) {
                throw new RuntimeException("");
            }
            // value
            String stringValue = value.toString();
            JsonElement after = JsonParser.parseString(stringValue).getAsJsonObject().getAsJsonObject("payload").get("after");

            String id = sha256(stringValue.getBytes());
            String chain = name.substring(0, name.length() - NAME_SUFFIX.length()) + sortedPayload.toString();
            JsonElement data = after.isJsonNull() ? JsonNull.INSTANCE : after;
            while (true) {
                try {
                    Response response = post(api, id, chain, data, store);
                    if (response.code == 201) {
                        LOGGER.info(String.format("CREATED id:%s(%s) chain:%s",
                                response.body.getAsJsonObject("data").getAsJsonObject("provable").get("id").getAsString(),
                                id,
                                chain));
                        break;
                    }
                    JsonObject error = response.body.getAsJsonObject("error");
                    if (response.code == 409 && error.get("code").getAsInt() == 3) {
                        LOGGER.warn(String.format("CONFLICT id:%s(%s) chain:%s",
                                error.getAsJsonObject("data").get("id").getAsString(),
                                id,
                                chain));
                        break;
                    }
                    LOGGER.warn(String.format("id:?(%s) chain:%s\n%s\n%s", id, chain, key.toString(), response));
                } catch (Exception e) {
                    LOGGER.error(e.getMessage(), key.toString());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    LOGGER.info(e.getMessage());
                }
            }
        });
        return streamsBuilder.build();
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(data);
            byte[] digest = messageDigest.digest();
            return DatatypeConverter.printHexBinary(digest).toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static Response post(String baseUrl, String id, String chain, JsonElement data, boolean store) throws Exception {
        byte[] bytes = data.toString().getBytes();
        String url = baseUrl + "/records?id=" + id +
                "&store=" + store +
                "&hash=" + sha256(bytes) +
                "&chain=" + URLEncoder.encode(chain, "utf-8");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("content-type", "application/octet-stream");
        connection.setDoOutput(true);
        connection.getOutputStream().write(bytes);
        connection.connect();
        JsonObject responseBody;
        try {
            responseBody = inputStreamToJson(connection.getInputStream());
        } catch (Exception e1) {
            responseBody = inputStreamToJson(connection.getErrorStream());
        }
        return new Response(url, connection.getResponseCode(), responseBody);
    }

    private static JsonObject inputStreamToJson(InputStream inputStream) {
        return JsonParser.parseReader(new BufferedReader(new InputStreamReader(inputStream))).getAsJsonObject();
    }

    public static class Response {

        String url;
        int code;
        JsonObject body;

        Response(String url, int code, JsonObject body) {
            this.url = url;
            this.code = code;
            this.body = body;
        }

        @Override
        public String toString() {
            return url + " " + code + "\n" + body;
        }

    }

}
