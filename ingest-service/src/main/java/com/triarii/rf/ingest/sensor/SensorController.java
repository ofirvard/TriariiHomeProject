package com.triarii.rf.ingest.sensor;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sensors")
public class SensorController {

    private final SensorRepository sensors;

    public SensorController(SensorRepository sensors) {
        this.sensors = sensors;
    }

    @GetMapping
    public List<SensorModel> list() {
        return sensors.findAll();
    }
}
