package com.triarii.rf.ingest.sensor;

import com.triarii.rf.ingest.observation.ObservationRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sensors")
public class SensorController {

    private final ObservationRepository observations;

    public SensorController(ObservationRepository observations) {
        this.observations = observations;
    }

    @GetMapping
    public List<SensorModel> list() {
        return observations.findSensors();
    }
}
