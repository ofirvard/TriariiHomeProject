package com.triarii.rf.ingest.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.triarii.rf.common.ObservationSnapshot;
import com.triarii.rf.ingest.config.IngestProperties;
import com.triarii.rf.ingest.engine.RuleEngine;
import com.triarii.rf.ingest.observation.IngestResult.ItemError;
import com.triarii.rf.ingest.sensor.SensorRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ObservationService {

    private final ObservationParser parser;
    private final ObservationRepository observations;
    private final SensorRepository sensors;
    private final TransactionTemplate transaction;
    private final RuleEngine engine;
    private final IngestProperties properties;
    private final Counter acceptedCounter;
    private final Counter rejectedCounter;

    public ObservationService(ObservationParser parser, ObservationRepository observations, SensorRepository sensors,
            TransactionTemplate transaction, RuleEngine engine, IngestProperties properties, MeterRegistry meters) {
        this.parser = parser;
        this.observations = observations;
        this.sensors = sensors;
        this.transaction = transaction;
        this.engine = engine;
        this.properties = properties;
        this.acceptedCounter = meters.counter("rf.observations", "result", "accepted");
        this.rejectedCounter = meters.counter("rf.observations", "result", "rejected");
    }

    /**
     * Accepts a single observation or an array. Partial acceptance: valid items are stored, invalid ones are
     * reported by index. Observations and sensor last-seen are written in one transaction; rules are evaluated
     * after the commit, on the stored rows (so events carry real observation ids).
     */
    public IngestResult ingest(JsonNode body) {
        List<JsonNode> items = items(body);

        List<NewObservation> valid = new ArrayList<>(items.size());
        List<ItemError> errors = new ArrayList<>();
        int rejected = 0;
        for (int i = 0; i < items.size(); i++) {
            ObservationParser.Parsed parsed = parser.parse(i, items.get(i));
            if (parsed instanceof ObservationParser.Valid v) {
                valid.add(v.observation());
            } else if (parsed instanceof ObservationParser.Invalid inv) {
                rejected++;
                errors.addAll(inv.errors());
            }
        }

        if (!valid.isEmpty()) {
            List<ObservationSnapshot> stored = transaction.execute(status -> {
                List<ObservationSnapshot> rows = observations.insertAll(valid);
                sensors.upsertLastSeen(valid);
                return rows;
            });
            engine.process(stored);
        }

        acceptedCounter.increment(valid.size());
        rejectedCounter.increment(rejected);
        return new IngestResult(valid.size(), rejected, errors);
    }

    public List<ObservationView> query(String sensorId, Double minMhz, Double maxMhz, Instant from, Instant to,
            int limit) {
        return observations.find(sensorId, minMhz, maxMhz, from, to, limit).stream()
                .map(ObservationView::from)
                .toList();
    }

    private List<JsonNode> items(JsonNode body) {
        List<JsonNode> items = new ArrayList<>();
        if (body.isArray()) {
            body.forEach(items::add);
        } else if (body.isObject()) {
            items.add(body);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body must be an observation object or an array");
        }
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batch is empty");
        }
        if (items.size() > properties.maxBatchSize()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "batch has " + items.size() + " items, max is " + properties.maxBatchSize());
        }
        return items;
    }
}
