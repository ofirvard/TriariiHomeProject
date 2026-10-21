package com.triarii.rf.ingest.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Incoming observation as sent by a sensor. Boxed types so a missing field fails validation instead of
 * silently becoming 0. The timestamp stays a string here and is parsed by {@link ObservationParser} so we
 * can enforce "ISO-8601 UTC" with a clear message.
 */
public record ObservationRequest(
        @NotBlank String sensorId,
        @NotBlank String timestamp,
        @NotNull @DecimalMin("1") @DecimalMax("6000") Double frequencyMhz,
        @NotNull @Positive Double bandwidthKhz,
        @NotNull @DecimalMin("-150") @DecimalMax("30") Double powerDbm,
        @NotNull @Valid Location location) {
}
