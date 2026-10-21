package com.triarii.rf.common;

import java.time.Instant;

/** A stored observation as carried inside events, so alert-service never has to call back into ingest-service. */
public record ObservationSnapshot(
        long id,
        String sensorId,
        Instant timestamp,
        double frequencyMhz,
        double bandwidthKhz,
        double powerDbm,
        double lat,
        double lon) {
}
