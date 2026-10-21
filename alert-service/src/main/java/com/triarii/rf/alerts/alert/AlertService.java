package com.triarii.rf.alerts.alert;

import com.triarii.rf.alerts.config.AlertProperties;
import com.triarii.rf.common.RuleMatchEvent;
import com.triarii.rf.common.Severity;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
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

    private final AlertRepository alerts;
    private final AlertProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    public AlertService(AlertRepository alerts, AlertProperties properties, Clock clock, MeterRegistry meters) {
        this.alerts = alerts;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Applies one rule match, idempotently: the event id is recorded in the same transaction, so a redelivered
     * event is a no-op. The active alert for the signal is row-locked while it is updated.
     */
    @Transactional
    public void apply(RuleMatchEvent event) {
        if (event.observations() == null || event.observations().isEmpty()) {
            log.warn("Ignoring event {} without observations", event.eventId());
            return;
        }
        if (!alerts.markProcessed(event.eventId())) {
            meters.counter("rf.events.duplicate").increment();
            return;
        }
        meters.counter("rf.events.consumed").increment();

        Optional<AlertModel> active = alerts.findActiveForUpdate(event.signalKey());
        AlertModel alert;
        if (active.isPresent() && active.get().expiredBefore(AlertModel.firstSeen(event.observations()))) {
            // The sweeper has not run yet, but this alert is already past resolve_after_s: close it, start anew.
            resolved(alerts.resolve(active.get().alertId(), "AUTO", clock.instant()), active.get().severity(), "AUTO");
            active = Optional.empty();
        }
        if (active.isPresent()) {
            alert = active.get().merge(event.observations());
            alerts.updateAggregates(alert);
        } else {
            alert = AlertModel.open(newAlertId(), event);
            alerts.insert(alert);
            meters.counter("rf.alerts.opened", "severity", alert.severity().name()).increment();
            log.info("Opened alert {} for {} ({})", alert.alertId(), alert.signalKey(), alert.severity());
        }
        alerts.addObservations(alert.alertId(), event.observations());
    }

    public List<AlertModel> find(AlertState state, Severity severity, String sensorId, Double minMhz, Double maxMhz,
            int limit) {
        return alerts.find(state, severity, sensorId, minMhz, maxMhz, limit);
    }

    public AlertDetails details(String alertId) {
        AlertModel alert = get(alertId);
        return new AlertDetails(alert, alerts.countObservations(alertId),
                alerts.latestObservations(alertId, properties.detailObservations()));
    }

    /** Idempotent: acknowledging an acknowledged alert is a no-op; a resolved alert cannot be acknowledged. */
    @Transactional
    public AlertModel acknowledge(String alertId) {
        if (!alerts.acknowledge(alertId, clock.instant())) {
            AlertModel current = get(alertId);
            if (current.state() == AlertState.RESOLVED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "alert " + alertId + " is already resolved");
            }
        }
        return get(alertId);
    }

    /** Idempotent: resolving a resolved alert is a no-op. A later match opens a new alert. */
    @Transactional
    public AlertModel resolve(String alertId) {
        AlertModel current = get(alertId);
        resolved(alerts.resolve(alertId, "MANUAL", clock.instant()), current.severity(), "MANUAL");
        return get(alertId);
    }

    /**
     * Auto-resolve. Alert times are event time, but silence can only be detected against a clock: an alert
     * resolves once its last match is older than resolve_after_s (+ grace for in-flight data) relative to now.
     * Safe because ingest bounds clock skew (future timestamps rejected) and lateness (stragglers not evaluated).
     */
    @Transactional
    public int resolveExpired(Instant now) {
        List<Severity> resolved = alerts.resolveExpired(now.minus(properties.resolveGrace()), now);
        resolved.forEach(severity -> resolved(true, severity, "AUTO"));
        if (!resolved.isEmpty()) {
            log.info("Auto-resolved {} alerts", resolved.size());
        }
        return resolved.size();
    }

    private void resolved(boolean changed, Severity severity, String reason) {
        if (changed) {
            meters.counter("rf.alerts.resolved", "reason", reason, "severity", severity.name()).increment();
        }
    }

    private AlertModel get(String alertId) {
        return alerts.findById(alertId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "alert " + alertId + " not found"));
    }

    private static String newAlertId() {
        byte[] bytes = new byte[4];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "alrt-" + HexFormat.of().formatHex(bytes);
    }
}
