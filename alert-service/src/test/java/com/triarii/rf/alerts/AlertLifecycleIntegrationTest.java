package com.triarii.rf.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.triarii.rf.alerts.alert.Alert;
import com.triarii.rf.alerts.alert.AlertService;
import com.triarii.rf.alerts.alert.AlertState;
import com.triarii.rf.common.Messaging;
import com.triarii.rf.common.Location;
import com.triarii.rf.common.Observation;
import com.triarii.rf.common.RuleMatchEvent;
import com.triarii.rf.common.Severity;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Alert lifecycle against real Postgres and RabbitMQ: open, dedup, idempotency, ack, auto-resolve, reopen. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AlertLifecycleIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private static final AtomicLong OBSERVATION_IDS = new AtomicLong();

    @Autowired
    TestRestTemplate http;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AlertService alerts;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Test
    void fullLifecycle() throws Exception {
        String signal = "rule-ism@2437.000";

        // 1. first event arrives over RabbitMQ -> a new OPEN alert
        rabbitTemplate.convertAndSend(Messaging.EXCHANGE, Messaging.RULE_MATCH_ROUTING_KEY,
                event(signal, obs("sensor-a", -12, -45), obs("sensor-a", -11, -42), obs("sensor-a", -10, -44)));
        Alert opened = awaitActive(signal);
        assertThat(opened.state()).isEqualTo(AlertState.OPEN);
        assertThat(opened.occurrences()).isEqualTo(3);
        assertThat(opened.peakPowerDbm()).isEqualTo(-42);
        assertThat(opened.firstSeen()).isEqualTo(now.minusSeconds(12));
        assertThat(opened.sensorIds()).containsExactly("sensor-a");

        // 2. same signal from another sensor -> same alert updated, not a new one
        RuleMatchEvent second = event(signal, obs("sensor-b", -9, -38));
        alerts.apply(second);
        Alert merged = active(signal);
        assertThat(merged.alertId()).isEqualTo(opened.alertId());
        assertThat(merged.occurrences()).isEqualTo(4);
        assertThat(merged.peakPowerDbm()).isEqualTo(-38);
        assertThat(merged.sensorIds()).containsExactly("sensor-a", "sensor-b");
        assertThat(merged.lastSeen()).isEqualTo(now.minusSeconds(9));

        // 3. redelivery of the same event id is a no-op
        alerts.apply(second);
        assertThat(active(signal).occurrences()).isEqualTo(4);

        // 4. acknowledge; further matches still update it and it stays ACKNOWLEDGED
        ResponseEntity<JsonNode> ack = http.postForEntity("/api/v1/alerts/" + opened.alertId() + "/ack", null,
                JsonNode.class);
        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ack.getBody().get("state").asText()).isEqualTo("ACKNOWLEDGED");
        alerts.apply(event(signal, obs("sensor-a", -5, -40)));
        Alert acked = active(signal);
        assertThat(acked.state()).isEqualTo(AlertState.ACKNOWLEDGED);
        assertThat(acked.occurrences()).isEqualTo(5);

        // 5. nothing for longer than resolve_after_s (60 s) + grace -> auto-resolved
        assertThat(alerts.resolveExpired(now.plusSeconds(30))).isZero();
        assertThat(alerts.resolveExpired(now.plus(Duration.ofSeconds(120)))).isEqualTo(1);
        JsonNode resolved = http.getForObject("/api/v1/alerts/" + opened.alertId(), JsonNode.class);
        assertThat(resolved.get("state").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolved_reason").asText()).isEqualTo("AUTO");
        assertThat(resolved.get("observation_count").asInt()).isEqualTo(5);
        assertThat(resolved.get("observations")).hasSize(5);

        // 6. a resolved alert cannot be acknowledged
        assertThat(http.postForEntity("/api/v1/alerts/" + opened.alertId() + "/ack", null, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 7. the emitter comes back -> a NEW alert
        alerts.apply(event(signal, obs("sensor-a", 0, -41)));
        Alert reopened = active(signal);
        assertThat(reopened.alertId()).isNotEqualTo(opened.alertId());
        assertThat(reopened.state()).isEqualTo(AlertState.OPEN);

        JsonNode open = http.getForObject("/api/v1/alerts?state=OPEN&sensor_id=sensor-a&min_mhz=2400&max_mhz=2500",
                JsonNode.class);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).get("alert_id").asText()).isEqualTo(reopened.alertId());
    }

    private Alert awaitActive(String signal) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Alert> found = activeAlerts(signal);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no active alert for " + signal);
    }

    private Alert active(String signal) {
        List<Alert> found = activeAlerts(signal);
        assertThat(found).as("exactly one active alert for " + signal).hasSize(1);
        return found.get(0);
    }

    private List<Alert> activeAlerts(String signal) {
        return alerts.find(null, null, null, null, null, 1000).stream()
                .filter(a -> a.signalKey().equals(signal) && a.state() != AlertState.RESOLVED)
                .toList();
    }

    private RuleMatchEvent event(String signal, Observation... observations) {
        return new RuleMatchEvent(UUID.randomUUID().toString(), "rule-ism", "power_threshold", signal, Severity.HIGH,
                60, List.of(observations));
    }

    private Observation obs(String sensorId, int secondsFromNow, double powerDbm) {
        return new Observation(OBSERVATION_IDS.incrementAndGet(), sensorId, now.plusSeconds(secondsFromNow),
                2437.0, 20000.0, powerDbm, new Location(32.08, 34.78));
    }
}
