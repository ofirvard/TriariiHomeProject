package com.triarii.rf.ingest.sensor;

import com.triarii.rf.common.LocationModel;
import java.time.Instant;

/** A known sensor; last_seen is the latest observation timestamp (event time), not arrival time. */
public record SensorModel(String sensorId, Instant lastSeen, LocationModel location) {
}
