package com.triarii.rf.ingest.observation;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triarii.rf.common.ObservationModel;
import com.triarii.rf.ingest.engine.RuleEngine;
import com.triarii.rf.ingest.observation.IngestResult.ItemError;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ObservationService {

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ObservationRepository observations;
    private final RuleEngine engine;
    private final int maxBatchSize;

    public ObservationService(ObjectMapper objectMapper, Validator validator, ObservationRepository observations,
            RuleEngine engine, @Value("${rf.max-batch-size}") int maxBatchSize) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.observations = observations;
        this.engine = engine;
        this.maxBatchSize = maxBatchSize;
    }

    /**
     * Accepts a single observation or an array. Valid items are stored and evaluated, invalid ones are reported
     * by index (partial acceptance).
     */
    public IngestResult ingest(JsonNode body) {
        List<JsonNode> items = items(body);
        List<ObservationModel> valid = new ArrayList<>();
        List<ItemError> errors = new ArrayList<>();
        int rejected = 0;
        for (int i = 0; i < items.size(); i++) {
            List<ItemError> itemErrors = new ArrayList<>();
            ObservationModel observation = parse(i, items.get(i), itemErrors);
            if (itemErrors.isEmpty()) {
                valid.add(observation);
            } else {
                rejected++;
                errors.addAll(itemErrors);
            }
        }
        if (!valid.isEmpty()) {
            engine.process(observations.insertAll(valid));
        }
        return new IngestResult(valid.size(), rejected, errors);
    }

    public List<ObservationModel> query(String sensorId, Double minMhz, Double maxMhz, Instant from, Instant to,
            int limit) {
        return observations.find(sensorId, minMhz, maxMhz, from, to, limit);
    }

    /** Binds and validates one item; adds to {@code errors} when it is invalid. */
    private ObservationModel parse(int index, JsonNode item, List<ItemError> errors) {
        ObservationModel observation;
        try {
            observation = objectMapper.treeToValue(item, ObservationModel.class);
        } catch (JsonMappingException e) {
            String field = e.getPath().stream().map(JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull).collect(Collectors.joining("."));
            errors.add(new ItemError(index, field.isEmpty() ? "$" : field, "has an invalid type or format"));
            return null;
        } catch (Exception e) {
            errors.add(new ItemError(index, "$", "is not a valid observation"));
            return null;
        }
        validator.validate(observation).forEach(v -> errors.add(new ItemError(index,
                v.getPropertyPath().toString().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(),
                v.getMessage())));
        return observation.withId(null); // the id is assigned by the database
    }

    private List<JsonNode> items(JsonNode body) {
        List<JsonNode> items = new ArrayList<>();
        if (body.isArray()) {
            body.forEach(items::add);
        } else {
            items.add(body);
        }
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batch is empty");
        }
        if (items.size() > maxBatchSize) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "batch has " + items.size() + " items, max is " + maxBatchSize);
        }
        return items;
    }
}
