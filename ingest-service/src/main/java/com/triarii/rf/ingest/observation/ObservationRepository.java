package com.triarii.rf.ingest.observation;

import com.triarii.rf.common.LocationModel;
import com.triarii.rf.common.ObservationModel;
import com.triarii.rf.ingest.sensor.SensorModel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ObservationRepository {

    private static final String INSERT = """
            INSERT INTO observations (sensor_id, ts, frequency_mhz, bandwidth_khz, power_dbm, lat, lon)
            VALUES (:sensorId, :ts, :frequencyMhz, :bandwidthKhz, :powerDbm, :lat, :lon)
            """;

    private static final String SELECT = """
            SELECT id, sensor_id, ts, frequency_mhz, bandwidth_khz, power_dbm, lat, lon
            FROM observations WHERE TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ObservationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Batch insert; returns the observations with their new ids, in input order. */
    public List<ObservationModel> insertAll(List<ObservationModel> observations) {
        SqlParameterSource[] params = observations.stream()
                .map(o -> new MapSqlParameterSource()
                        .addValue("sensorId", o.sensorId())
                        .addValue("ts", utc(o.timestamp()))
                        .addValue("frequencyMhz", o.frequencyMhz())
                        .addValue("bandwidthKhz", o.bandwidthKhz())
                        .addValue("powerDbm", o.powerDbm())
                        .addValue("lat", o.location().lat())
                        .addValue("lon", o.location().lon()))
                .toArray(SqlParameterSource[]::new);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.batchUpdate(INSERT, params, keys, new String[] {"id"});

        List<ObservationModel> stored = new ArrayList<>(observations.size());
        for (int i = 0; i < observations.size(); i++) {
            long id = ((Number) keys.getKeyList().get(i).get("id")).longValue();
            stored.add(observations.get(i).withId(id));
        }
        return stored;
    }

    /** All filters are optional (null = not applied). Newest first. */
    public List<ObservationModel> find(String sensorId, Double minMhz, Double maxMhz, Instant from, Instant to,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
        if (sensorId != null) {
            sql.append(" AND sensor_id = :sensorId");
            params.addValue("sensorId", sensorId);
        }
        if (minMhz != null) {
            sql.append(" AND frequency_mhz >= :minMhz");
            params.addValue("minMhz", minMhz);
        }
        if (maxMhz != null) {
            sql.append(" AND frequency_mhz <= :maxMhz");
            params.addValue("maxMhz", maxMhz);
        }
        if (from != null) {
            sql.append(" AND ts >= :from");
            params.addValue("from", utc(from));
        }
        if (to != null) {
            sql.append(" AND ts <= :to");
            params.addValue("to", utc(to));
        }
        sql.append(" ORDER BY ts DESC, id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, ObservationRepository::map);
    }

    /** Known sensors and their last-seen time (event time), derived from the observations. */
    public List<SensorModel> findSensors() {
        return jdbc.query(
                "SELECT sensor_id, max(ts) AS last_seen FROM observations GROUP BY sensor_id ORDER BY sensor_id",
                (rs, row) -> new SensorModel(rs.getString("sensor_id"),
                        rs.getObject("last_seen", OffsetDateTime.class).toInstant()));
    }

    private static ObservationModel map(ResultSet rs, int row) throws SQLException {
        return new ObservationModel(
                rs.getLong("id"),
                rs.getString("sensor_id"),
                rs.getObject("ts", OffsetDateTime.class).toInstant(),
                rs.getDouble("frequency_mhz"),
                rs.getDouble("bandwidth_khz"),
                rs.getDouble("power_dbm"),
                new LocationModel(rs.getDouble("lat"), rs.getDouble("lon")));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
