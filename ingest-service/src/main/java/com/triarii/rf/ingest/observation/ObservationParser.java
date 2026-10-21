package com.triarii.rf.ingest.observation;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.triarii.rf.common.Observation;
import com.triarii.rf.ingest.config.IngestProperties;
import com.triarii.rf.ingest.observation.IngestResult.ItemError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Turns one JSON item into a validated {@link Observation}, or into the list of reasons it was rejected. */
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

    public record Valid(Observation observation) implements Parsed {
    }

    public record Invalid(List<ItemError> errors) implements Parsed {
    }

    public Parsed parse(int index, JsonNode item) {
        if (!item.isObject()) {
            return invalid(index, "$", "must be a JSON object");
        }
        ObjectNode node = ((ObjectNode) item).deepCopy();
        node.remove("id"); // assigned by the database
        List<ItemError> errors = new ArrayList<>();

        // Checked on the raw JSON: Jackson alone would also accept offsets like +03:00 or epoch numbers.
        JsonNode timestamp = node.get("timestamp");
        if (timestamp != null && !timestamp.isNull() && !isUtc(timestamp)) {
            errors.add(new ItemError(index, "timestamp", UTC_HINT));
            node.remove("timestamp");
        }

        Observation observation;
        try {
            observation = objectMapper.treeToValue(node, Observation.class);
        } catch (JsonMappingException e) {
            return invalid(index, fieldOf(e), "has an invalid type or format");
        } catch (Exception e) {
            return invalid(index, "$", "could not be read");
        }

        boolean timestampReported = !errors.isEmpty();
        for (ConstraintViolation<Observation> violation : validator.validate(observation)) {
            String field = snakeCase(violation);
            if (!(timestampReported && field.equals("timestamp"))) {
                errors.add(new ItemError(index, field, violation.getMessage()));
            }
        }
        if (observation.timestamp() != null
                && observation.timestamp().isAfter(clock.instant().plus(properties.maxFutureSkew()))) {
            errors.add(new ItemError(index, "timestamp",
                    "is too far in the future (max skew " + properties.maxFutureSkew() + ")"));
        }

        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparing(ItemError::field));
            return new Invalid(errors);
        }
        return new Valid(observation);
    }

    /** Only an explicit UTC 'Z' suffix is accepted. */
    private static boolean isUtc(JsonNode value) {
        if (!value.isTextual() || !value.asText().endsWith("Z")) {
            return false;
        }
        try {
            Instant.parse(value.asText());
            return true;
        } catch (DateTimeParseException e) {
            return false;
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
