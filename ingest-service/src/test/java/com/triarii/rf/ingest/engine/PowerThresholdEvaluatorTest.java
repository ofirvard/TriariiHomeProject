package com.triarii.rf.ingest.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.triarii.rf.common.ObservationSnapshot;
import com.triarii.rf.common.RuleMatchEvent;
import com.triarii.rf.common.Severity;
import com.triarii.rf.ingest.config.IngestProperties;
import com.triarii.rf.ingest.rule.Rule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PowerThresholdEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-10-06T10:00:00Z");

    // band 2400-2483.5 MHz, > -50 dBm for >= 10 s; max_gap 5 s
    private static final Rule RULE = new Rule("rule-ism", "ISM high power", Rule.POWER_THRESHOLD,
            new Rule.Band(2400.0, 2483.5), Severity.HIGH, 60, true, -50.0, 10, null, null, null);

    private final PowerThresholdEvaluator evaluator = new PowerThresholdEvaluator(new IngestProperties(
            "classpath:rules.yaml", 1000, Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(5),
            0.1, 500));

    private long nextId = 1;

    @Test
    void persistentSignalFiresOnceMinDurationIsCovered() {
        assertThat(feed("sensor-03", IntStream.rangeClosed(0, 9), 2437.0, -40)).isEmpty();

        List<RuleMatchEvent> atTenSeconds = feed("sensor-03", IntStream.of(10), 2437.0, -40);
        assertThat(atTenSeconds).hasSize(1);
        RuleMatchEvent first = atTenSeconds.get(0);
        assertThat(first.ruleId()).isEqualTo("rule-ism");
        assertThat(first.severity()).isEqualTo(Severity.HIGH);
        assertThat(first.resolveAfterS()).isEqualTo(60);
        assertThat(first.observations()).hasSize(11); // the whole pending window, 0..10 s

        List<RuleMatchEvent> later = feed("sensor-03", IntStream.of(11, 12), 2437.0, -40);
        assertThat(later).hasSize(2).allSatisfy(e -> assertThat(e.observations()).hasSize(1));
    }

    @Test
    void shortBurstDoesNotFire() {
        assertThat(feed("sensor-05", IntStream.rangeClosed(0, 3), 2440.0, -30)).isEmpty();
    }

    @Test
    void outOfOrderObservationsStillFireWithSortedWindow() {
        List<RuleMatchEvent> events = feed("sensor-03", IntStream.of(1, 0, 3, 2, 5, 4, 7, 6, 9, 8, 10), 2437.0, -40);

        assertThat(events).hasSize(1);
        List<Instant> timestamps = events.get(0).observations().stream().map(ObservationSnapshot::timestamp).toList();
        assertThat(timestamps).hasSize(11).isSorted();
    }

    @Test
    void gapLargerThanMaxGapResetsPendingWindow() {
        assertThat(feed("sensor-03", IntStream.rangeClosed(0, 5), 2437.0, -40)).isEmpty();
        // 6 s gap (> 5 s): the window restarts at 12 s, so 12..21 only covers 9 s
        assertThat(feed("sensor-03", IntStream.rangeClosed(12, 21), 2437.0, -40)).isEmpty();

        List<RuleMatchEvent> events = feed("sensor-03", IntStream.of(22), 2437.0, -40);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).observations()).hasSize(11); // 12..22 s, nothing from the first burst
    }

    @Test
    void ignoresObservationsOutsideBandOrNotAboveThreshold() {
        assertThat(feed("sensor-01", IntStream.rangeClosed(0, 20), 2500.0, -40)).isEmpty();
        assertThat(feed("sensor-02", IntStream.rangeClosed(0, 20), 2437.0, -60)).isEmpty();
        assertThat(feed("sensor-04", IntStream.rangeClosed(0, 20), 2437.0, -50)).isEmpty(); // strictly above
    }

    @Test
    void anotherSensorJoiningAFiringSignalIsPublishedImmediately() {
        RuleMatchEvent first = feed("sensor-a", IntStream.rangeClosed(0, 10), 2437.02, -40).get(0);

        // sensor-b only sees it for one observation, but the signal is already alerting
        List<RuleMatchEvent> joined = feed("sensor-b", IntStream.of(11), 2436.98, -45);

        assertThat(joined).hasSize(1);
        assertThat(joined.get(0).signalKey()).isEqualTo(first.signalKey()).isEqualTo("rule-ism@2437.000");
        assertThat(joined.get(0).observations().get(0).sensorId()).isEqualTo("sensor-b");
    }

    @Test
    void persistenceIsPerSignalAcrossSensors() {
        // two sensors alternating on the same emitter: together they cover 10 s
        List<RuleMatchEvent> events = new ArrayList<>();
        IntStream.rangeClosed(0, 10).forEach(s -> events.addAll(
                feed(s % 2 == 0 ? "sensor-a" : "sensor-b", IntStream.of(s), 2437.0, -40)));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).observations()).extracting(ObservationSnapshot::sensorId)
                .containsOnly("sensor-a", "sensor-b");
    }

    @Test
    void forgettingARuleDropsItsPendingWindows() {
        feed("sensor-03", IntStream.rangeClosed(0, 9), 2437.0, -40);
        evaluator.forget("rule-ism");

        assertThat(feed("sensor-03", IntStream.of(10), 2437.0, -40)).isEmpty();
    }

    @Test
    void evictionDropsIdleWindows() {
        feed("sensor-03", IntStream.rangeClosed(0, 3), 2437.0, -40);
        assertThat(evaluator.windowCount()).isEqualTo(1);

        evaluator.evictBefore(T0.plusSeconds(60));

        assertThat(evaluator.windowCount()).isZero();
    }

    private List<RuleMatchEvent> feed(String sensorId, IntStream seconds, double frequencyMhz, double powerDbm) {
        List<RuleMatchEvent> events = new ArrayList<>();
        seconds.forEach(s -> events.addAll(evaluator.evaluate(RULE, new ObservationSnapshot(nextId++, sensorId,
                T0.plusSeconds(s), frequencyMhz, 20000, powerDbm, 32.08, 34.78))));
        return events;
    }
}
