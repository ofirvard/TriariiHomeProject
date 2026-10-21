package com.triarii.rf.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * The one observation model: request body, stored row, query result and event payload.
 *
 * <p>Fields are boxed so a missing field fails validation instead of silently becoming 0. The id is null until
 * the observation is stored, and is ignored on input.
 */
public record Observation(
        Long id,
        @NotBlank String sensorId,
        @NotNull Instant timestamp,
        @NotNull @DecimalMin("1") @DecimalMax("6000") Double frequencyMhz,
        @NotNull @Positive Double bandwidthKhz,
        @NotNull @DecimalMin("-150") @DecimalMax("30") Double powerDbm,
        @NotNull @Valid Location location) {

    public Observation withId(Long newId) {
        return new Observation(newId, sensorId, timestamp, frequencyMhz, bandwidthKhz, powerDbm, location);
    }
}
