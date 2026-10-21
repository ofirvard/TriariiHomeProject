package com.triarii.rf.alerts.alert;

import com.triarii.rf.common.Severity;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private static final int MAX_LIMIT = 1000;

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    @GetMapping
    public List<AlertModel> list(
            @RequestParam(name = "state", required = false) AlertState state,
            @RequestParam(name = "severity", required = false) Severity severity,
            @RequestParam(name = "sensor_id", required = false) String sensorId,
            @RequestParam(name = "min_mhz", required = false) Double minMhz,
            @RequestParam(name = "max_mhz", required = false) Double maxMhz,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        if (minMhz != null && maxMhz != null && minMhz > maxMhz) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "min_mhz must be <= max_mhz");
        }
        return service.find(state, severity, sensorId, minMhz, maxMhz, limit);
    }

    @GetMapping("/{alertId}")
    public AlertDetails get(@PathVariable String alertId) {
        return service.details(alertId);
    }

    @PostMapping("/{alertId}/ack")
    public AlertModel acknowledge(@PathVariable String alertId) {
        return service.acknowledge(alertId);
    }

    @PostMapping("/{alertId}/resolve")
    public AlertModel resolve(@PathVariable String alertId) {
        return service.resolve(alertId);
    }
}
