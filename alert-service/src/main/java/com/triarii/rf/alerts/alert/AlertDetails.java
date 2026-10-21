package com.triarii.rf.alerts.alert;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.triarii.rf.common.ObservationModel;
import java.util.List;

/** GET /alerts/{id}: the alert plus its contributing observations (latest first, capped). */
public record AlertDetails(@JsonUnwrapped AlertModel alert, List<ObservationModel> observations) {
}
