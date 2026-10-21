package com.triarii.rf.alerts.alert;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically auto-resolves alerts whose emitter has gone quiet. */
@Component
public class AlertSweeper {

    private final AlertService alerts;
    private final Clock clock;

    public AlertSweeper(AlertService alerts, Clock clock) {
        this.alerts = alerts;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${rf.sweep-interval-ms:5000}")
    void sweep() {
        alerts.resolveExpired(clock.instant());
    }
}
