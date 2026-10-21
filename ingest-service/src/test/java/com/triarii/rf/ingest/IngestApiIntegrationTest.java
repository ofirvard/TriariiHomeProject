package com.triarii.rf.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triarii.rf.common.Messaging;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Full stack against real Postgres and RabbitMQ: HTTP in, rows in the database, events on the queue. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IngestApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ObjectMapper mapper;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Test
    void mixedBatchStoresValidItemsAndReportsInvalidOnes() throws Exception {
        String batch = "[" + observation("it-sensor-1", now.minusSeconds(5), 915.0, -90) + ","
                + "{\"sensor_id\":\"it-sensor-1\",\"timestamp\":\"" + now + "\",\"frequency_mhz\":7000,"
                + "\"bandwidth_khz\":125,\"power_dbm\":-90,\"location\":{\"lat\":32.1,\"lon\":34.8}},"
                + observation("it-sensor-2", now, 868.0, -95) + "]";

        ResponseEntity<JsonNode> response = post("/api/v1/observations", batch);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("accepted").asInt()).isEqualTo(2);
        assertThat(response.getBody().get("rejected").asInt()).isEqualTo(1);
        JsonNode error = response.getBody().get("errors").get(0);
        assertThat(error.get("index").asInt()).isEqualTo(1);
        assertThat(error.get("field").asText()).isEqualTo("frequency_mhz");

        JsonNode found = http.getForObject("/api/v1/observations?sensor_id=it-sensor-1&min_mhz=900&max_mhz=930",
                JsonNode.class);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("frequency_mhz").asDouble()).isEqualTo(915.0);

        JsonNode sensors = http.getForObject("/api/v1/sensors", JsonNode.class);
        List<String> ids = new ArrayList<>();
        sensors.forEach(s -> ids.add(s.get("sensor_id").asText()));
        assertThat(ids).contains("it-sensor-1", "it-sensor-2");
    }

    @Test
    void persistentEmitterPublishesRuleMatchWithTheWholeWindow() throws Exception {
        // seeded rule-ism-high-power: 2400-2483.5 MHz, > -50 dBm for >= 10 s
        StringBuilder batch = new StringBuilder("[");
        IntStream.rangeClosed(0, 10).forEach(s -> batch.append(s == 0 ? "" : ",")
                .append(observation("it-emitter", now.minusSeconds(30 - s), 2437.0, -40)));
        batch.append("]");

        assertThat(post("/api/v1/observations", batch.toString()).getStatusCode()).isEqualTo(HttpStatus.OK);

        Message message = rabbitTemplate.receive(Messaging.RULE_MATCH_QUEUE, 10_000);
        assertThat(message).as("rule match event on the queue").isNotNull();
        JsonNode event = mapper.readTree(message.getBody());
        assertThat(event.get("rule_id").asText()).isEqualTo("rule-ism-high-power");
        assertThat(event.get("severity").asText()).isEqualTo("HIGH");
        assertThat(event.get("signal_key").asText()).isEqualTo("rule-ism-high-power@2437.000");
        assertThat(event.get("observations")).hasSize(11);
        assertThat(event.get("observations").get(0).get("id").asLong()).isPositive();
    }

    private ResponseEntity<JsonNode> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(path, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private static String observation(String sensorId, Instant timestamp, double frequencyMhz, double powerDbm) {
        return "{\"sensor_id\":\"" + sensorId + "\",\"timestamp\":\"" + timestamp + "\",\"frequency_mhz\":"
                + frequencyMhz + ",\"bandwidth_khz\":20000,\"power_dbm\":" + powerDbm
                + ",\"location\":{\"lat\":32.08,\"lon\":34.78}}";
    }
}
