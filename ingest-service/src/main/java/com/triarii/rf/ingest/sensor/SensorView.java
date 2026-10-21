package com.triarii.rf.ingest.sensor;

import com.triarii.rf.ingest.observation.Location;
import java.time.Instant;

/** A known sensor; last_seen is the latest observation timestamp (event time), not arrival time. */
public record SensorView(String sensorId, Instant lastSeen, Location location) {
}
