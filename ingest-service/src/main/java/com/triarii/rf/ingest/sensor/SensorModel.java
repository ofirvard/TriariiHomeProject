package com.triarii.rf.ingest.sensor;

import java.time.Instant;

/** A known sensor; last_seen is its latest observation timestamp (event time), not arrival time. */
public record SensorModel(String sensorId, Instant lastSeen) {
}
