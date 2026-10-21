package com.triarii.rf.ingest.sensor;

import com.triarii.rf.ingest.observation.Location;
import com.triarii.rf.ingest.observation.NewObservation;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class SensorRepository {

    // GREATEST() keeps last_seen monotonic, so a late/out-of-order observation never moves it backwards;
    // the location follows whichever observation is the newest.
    private static final String UPSERT = """
            INSERT INTO sensors (sensor_id, last_seen, lat, lon)
            VALUES (:sensorId, :lastSeen, :lat, :lon)
            ON CONFLICT (sensor_id) DO UPDATE SET
                lat = CASE WHEN EXCLUDED.last_seen > sensors.last_seen THEN EXCLUDED.lat ELSE sensors.lat END,
                lon = CASE WHEN EXCLUDED.last_seen > sensors.last_seen THEN EXCLUDED.lon ELSE sensors.lon END,
                last_seen = GREATEST(sensors.last_seen, EXCLUDED.last_seen)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SensorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One upsert per sensor, using that sensor's newest observation in the batch. */
    public void upsertLastSeen(List<NewObservation> observations) {
        BinaryOperator<NewObservation> newest = BinaryOperator.maxBy(Comparator.comparing(NewObservation::timestamp));
        Collection<NewObservation> latest = observations.stream()
                .collect(Collectors.toMap(NewObservation::sensorId, o -> o, newest))
                .values();
        SqlParameterSource[] params = latest.stream()
                .sorted(Comparator.comparing(NewObservation::sensorId)) // stable lock order across requests
                .map(o -> new MapSqlParameterSource()
                        .addValue("sensorId", o.sensorId())
                        .addValue("lastSeen", o.timestamp().atOffset(ZoneOffset.UTC))
                        .addValue("lat", o.lat())
                        .addValue("lon", o.lon()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT, params);
    }

    public List<SensorView> findAll() {
        return jdbc.query("SELECT sensor_id, last_seen, lat, lon FROM sensors ORDER BY sensor_id",
                (rs, row) -> new SensorView(
                        rs.getString("sensor_id"),
                        rs.getObject("last_seen", OffsetDateTime.class).toInstant(),
                        new Location(rs.getDouble("lat"), rs.getDouble("lon"))));
    }
}
