package com.triarii.rf.ingest.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.triarii.rf.common.Observation;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/observations")
public class ObservationController {

    private static final int MAX_LIMIT = 1000;

    private final ObservationService service;

    public ObservationController(ObservationService service) {
        this.service = service;
    }

    /** 200 = all accepted, 207 = some rejected, 422 = all rejected. */
    @PostMapping
    public ResponseEntity<IngestResult> ingest(@RequestBody JsonNode body) {
        IngestResult result = service.ingest(body);
        HttpStatus status = result.rejected() == 0 ? HttpStatus.OK
                : result.accepted() == 0 ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.MULTI_STATUS;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping
    public List<Observation> query(
            @RequestParam(name = "sensor_id", required = false) String sensorId,
            @RequestParam(name = "min_mhz", required = false) Double minMhz,
            @RequestParam(name = "max_mhz", required = false) Double maxMhz,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        if (minMhz != null && maxMhz != null && minMhz > maxMhz) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "min_mhz must be <= max_mhz");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be <= to");
        }
        return service.query(sensorId, minMhz, maxMhz, from, to, limit);
    }
}
