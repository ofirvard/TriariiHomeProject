package com.triarii.rf.alerts.alert;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.triarii.rf.common.ObservationModel;
import java.time.Instant;
import java.util.List;

/**
 * GET /alerts/{id}: the alert, when it was acknowledged / resolved and why, and its contributing observations
 * (latest first, capped; the total is in observation_count).
 */
public record AlertDetails(
        @JsonUnwrapped AlertModel alert,
        @JsonUnwrapped Lifecycle lifecycle,
        int observationCount,
        List<ObservationModel> observations) {

    /** Wall-clock audit times; null until the transition happens. resolvedReason is AUTO or MANUAL. */
    public record Lifecycle(Instant acknowledgedAt, Instant resolvedAt, String resolvedReason) {
    }
}
