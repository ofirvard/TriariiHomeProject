package com.triarii.rf.ingest.observation;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triarii.rf.ingest.config.IngestProperties;
import com.triarii.rf.ingest.observation.IngestResult.ItemError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Turns one JSON item into a {@link NewObservation}, or into the list of reasons it was rejected. */
@Component
public class ObservationParser {

    private static final String UTC_HINT = "must be ISO-8601 UTC, e.g. 2026-10-06T10:15:30Z";

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final IngestProperties properties;
    private final Clock clock;

    public ObservationParser(ObjectMapper objectMapper, Validator validator, IngestProperties properties,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
    }

    public sealed interface Parsed permits Valid, Invalid {
    }

    public record Valid(NewObservation observation) implements Parsed {
    }

    public record Invalid(List<ItemError> errors) implements Parsed {
    }

    public Parsed parse(int index, JsonNode item) {
        if (!item.isObject()) {
            return invalid(index, "$", "must be a JSON object");
        }

        ObservationRequest request;
        try {
            request = objectMapper.treeToValue(item, ObservationRequest.class);
        } catch (JsonMappingException e) {
            return invalid(index, fieldOf(e), "has an invalid type or format");
        } catch (Exception e) {
            return invalid(index, "$", "could not be read");
        }

        List<ItemError> errors = validator.validate(request).stream()
                .map(v -> new ItemError(index, snakeCase(v), v.getMessage()))
                .collect(Collectors.toList());

        Instant timestamp = null;
        if (request.timestamp() != null && !request.timestamp().isBlank()) {
            timestamp = parseUtc(request.timestamp());
            if (timestamp == null) {
                errors.add(new ItemError(index, "timestamp", UTC_HINT));
            } else if (timestamp.isAfter(clock.instant().plus(properties.maxFutureSkew()))) {
                errors.add(new ItemError(index, "timestamp",
                        "is too far in the future (max skew " + properties.maxFutureSkew() + ")"));
            }
        }

        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparing(ItemError::field));
            return new Invalid(errors);
        }

        return new Valid(new NewObservation(request.sensorId(), timestamp, request.frequencyMhz(),
                request.bandwidthKhz(), request.powerDbm(), request.location().lat(), request.location().lon()));
    }

    /** Only an explicit UTC 'Z' suffix is accepted; returns null when the value is not ISO-8601 UTC. */
    private static Instant parseUtc(String value) {
        if (!value.endsWith("Z")) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Invalid invalid(int index, String field, String message) {
        return new Invalid(List.of(new ItemError(index, field, message)));
    }

    private static String fieldOf(JsonMappingException e) {
        String path = e.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(name -> name != null)
                .collect(Collectors.joining("."));
        return path.isEmpty() ? "$" : path;
    }

    private static String snakeCase(ConstraintViolation<?> violation) {
        return violation.getPropertyPath().toString().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
