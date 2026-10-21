package com.triarii.rf.ingest.observation;

import java.time.Instant;

/** A validated observation that has not been stored yet. */
public record NewObservation(
        String sensorId,
        Instant timestamp,
        double frequencyMhz,
        double bandwidthKhz,
        double powerDbm,
        double lat,
        double lon) {
}
