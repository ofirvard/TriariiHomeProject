package com.triarii.rf.ingest.observation;

import com.triarii.rf.common.ObservationSnapshot;
import java.time.Instant;

/** API representation of a stored observation; same shape as the request plus its id. */
public record ObservationView(
        long id,
        String sensorId,
        Instant timestamp,
        double frequencyMhz,
        double bandwidthKhz,
        double powerDbm,
        Location location) {

    static ObservationView from(ObservationSnapshot o) {
        return new ObservationView(o.id(), o.sensorId(), o.timestamp(), o.frequencyMhz(), o.bandwidthKhz(),
                o.powerDbm(), new Location(o.lat(), o.lon()));
    }
}
