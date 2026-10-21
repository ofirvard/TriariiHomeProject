package com.triarii.rf.alerts.alert;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.triarii.rf.common.Observation;
import java.util.List;

/** Alert fields plus its contributing observations (latest first, capped; the total is in observation_count). */
public record AlertDetails(
        @JsonUnwrapped Alert alert,
        int observationCount,
        List<Observation> observations) {
}
