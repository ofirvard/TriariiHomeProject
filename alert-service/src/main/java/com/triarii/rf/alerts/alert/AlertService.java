package com.triarii.rf.alerts.alert;

import com.triarii.rf.common.ObservationModel;
import com.triarii.rf.common.RuleMatchEvent;
import com.triarii.rf.common.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** Extra wait on top of resolve_after_s, so data still in flight does not resolve then reopen an alert. */
    static final Duration RESOLVE_GRACE = Duration.ofSeconds(10);
    private static final int DETAIL_OBSERVATIONS = 100;

    private final AlertRepository alerts;

    public AlertService(AlertRepository alerts) {
        this.alerts = alerts;
    }

    /**
     * Adds a rule match to the active alert of its signal, or opens a new one. Acknowledged alerts keep updating.
     * Redelivery is harmless: an observation is stored once per alert and the summary is derived from them.
     */
    @Transactional
    public void apply(RuleMatchEvent event) {
        if (event.observations().isEmpty()) {
            return;
        }
        String alertId = alerts.findActiveIdForUpdate(event.signalKey()).orElseGet(() -> open(event));
        alerts.addObservations(alertId, event.observations());
    }

    public List<AlertModel> find(AlertState state, Severity severity, String sensorId, Double minMhz, Double maxMhz,
            int limit) {
        return alerts.find(state, severity, sensorId, minMhz, maxMhz, limit);
    }

    public AlertDetails details(String alertId) {
        return new AlertDetails(get(alertId), alerts.latestObservations(alertId, DETAIL_OBSERVATIONS));
    }

    /** Acknowledging an acknowledged alert is a no-op; a resolved alert cannot be acknowledged. */
    @Transactional
    public AlertModel acknowledge(String alertId) {
        if (!alerts.acknowledge(alertId) && get(alertId).state() == AlertState.RESOLVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "alert " + alertId + " is already resolved");
        }
        return get(alertId);
    }

    /** Resolving a resolved alert is a no-op. A later match opens a new alert. */
    @Transactional
    public AlertModel resolve(String alertId) {
        alerts.resolve(alertId);
        return get(alertId);
    }

    /**
     * Auto-resolve. Alert times are event time, but silence can only be detected against a clock: an alert
     * resolves once its last observation is older than resolve_after_s (+ grace) relative to now.
     */
    public int resolveExpired(Instant now) {
        int resolved = alerts.resolveExpired(now.minus(RESOLVE_GRACE));
        if (resolved > 0) {
            log.info("Auto-resolved {} alerts", resolved);
        }
        return resolved;
    }

    private String open(RuleMatchEvent event) {
        byte[] random = new byte[4];
        ThreadLocalRandom.current().nextBytes(random);
        String alertId = "alrt-" + HexFormat.of().formatHex(random);
        ObservationModel earliest = event.observations().stream()
                .min(Comparator.comparing(ObservationModel::timestamp)).orElseThrow();
        alerts.insert(alertId, event, earliest.frequencyMhz());
        log.info("Opened alert {} for {}", alertId, event.signalKey());
        return alertId;
    }

    private AlertModel get(String alertId) {
        return alerts.findById(alertId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "alert " + alertId + " not found"));
    }
}
