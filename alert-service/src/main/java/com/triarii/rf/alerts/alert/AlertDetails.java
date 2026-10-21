package com.triarii.rf.alerts.alert;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.triarii.rf.common.ObservationModel;
import java.util.List;

/** Alert fields plus its contributing observations (latest first, capped; the total is in observation_count). */
public record AlertDetails(
        @JsonUnwrapped AlertModel alert,
        int observationCount,
        List<ObservationModel> observations) {
}
