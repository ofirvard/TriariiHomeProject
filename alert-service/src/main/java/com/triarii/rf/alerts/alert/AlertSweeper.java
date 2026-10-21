package com.triarii.rf.alerts.alert;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically auto-resolves alerts whose emitter has gone quiet. */
@Component
public class AlertSweeper {

    private final AlertService alerts;

    public AlertSweeper(AlertService alerts) {
        this.alerts = alerts;
    }

    @Scheduled(fixedDelay = 5_000)
    void sweep() {
        alerts.resolveExpired(Instant.now());
    }
}
