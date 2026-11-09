package mx.velora.api.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mx.velora.api.IntegrationTest;
import mx.velora.api.activity.Metric.Source;
import mx.velora.api.activity.NewActivity.MetricValue;
import mx.velora.api.activity.persistence.StreamCodec;
import mx.velora.api.identity.user.OwnerBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class ActivityRegistrationIntegrationTest {

    /** 23:55 del 08/11 en Ciudad de México = 05:55Z del 09/11. */
    private static final Instant START = Instant.parse("2026-11-09T05:55:00Z");

    private static final ZoneId MEXICO_CITY = ZoneId.of("America/Mexico_City");

    @Autowired
    ActivityRegistration registration;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    private UUID userId;

    @BeforeEach
    void owner() {
        jdbc.sql("TRUNCATE users CASCADE").update();
        userId = bootstrap.createOwner("propietario-" + UUID.randomUUID() + "@example.com", "contraseña-de-prueba");
    }

    /** 6 muestras: segmento 0 con 3 posiciones, segmento 1 con 2 posiciones y una muestra sin posición. */
    private static NewActivity.Samples samples() {
        var streams = new EnumMap<StreamType, Double[]>(StreamType.class);
        streams.put(StreamType.TIME, new Double[] {0.0, 1_000.0, 2_000.0, 600_000.0, 601_000.0, 602_000.0});
        streams.put(StreamType.LATITUDE, new Double[] {19.0, 19.0001, 19.0002, 19.01, 19.0101, null});
        streams.put(StreamType.LONGITUDE, new Double[] {-99.0, -99.0001, -99.0002, -99.01, -99.0101, null});
        streams.put(StreamType.ALTITUDE, new Double[] {2_000.0, 2_000.5, null, 2_010.0, 2_010.2, 2_010.4});
        streams.put(StreamType.HEART_RATE, new Double[6]); // sin sensor: no se guarda
        return new NewActivity.Samples(6, new int[] {0, 0, 0, 1, 1, 1}, streams);
    }

    private static List<MetricValue> metrics() {
        var params = Map.of("movingSpeedThresholdMps", 1.0, "elevationHysteresisMeters", 1.0);
        return List.of(
                new MetricValue(Metric.ELAPSED_TIME, Source.DERIVED, 602, "derived-metrics/1", params),
                new MetricValue(Metric.MOVING_TIME, Source.DERIVED, 4, "derived-metrics/1", params),
                new MetricValue(Metric.MOVING_TIME, Source.DEVICE_REPORTED, 5, null, null),
                new MetricValue(Metric.DISTANCE, Source.DERIVED, 1_234.5, "derived-metrics/1", params),
                new MetricValue(Metric.ELEVATION_GAIN, Source.DERIVED, 10.4, "derived-metrics/1", params));
    }

    private static byte[] sha(int seed) {
        var bytes = new byte[32];
        Arrays.fill(bytes, (byte) seed);
        return bytes;
    }

    private NewActivity activity(String title, byte[] sha256) {
        return new NewActivity(
                userId,
                null,
                null,
                title,
                ActivitySubType.ROAD,
                START,
                MEXICO_CITY,
                "FIT",
                "fit:255:1:123",
                metrics(),
                samples(),
                List.of(new NewActivity.Lap(1, START, 602.0, 5.0, 1_234.5, null, null, null, null, null, null, null)),
                new NewActivity.OriginalFile(userId + "/" + UUID.randomUUID(), sha256, 4_096, "velora-fit", "1"));
    }

    @Test
    void registersActivityWithLocalDateDefaultTitleAndEffectiveMetrics() {
        var id = registration.register(activity(null, sha(1)));

        var row = jdbc.sql("""
                        SELECT title, local_start_date, time_zone_at_import, sub_type, moving_time_seconds,
                               distance_meters, elevation_gain_meters, average_heart_rate_bpm,
                               metrics_provenance ->> 'movingTimeSeconds' AS moving_source,
                               metrics_provenance ->> 'distanceMeters' AS distance_source
                        FROM activities WHERE id = :id
                        """).param("id", id).query().singleRow();
        assertThat(row.get("title")).isEqualTo("Rodada del 08/11/2026");
        assertThat(((java.sql.Date) row.get("local_start_date")).toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(row.get("time_zone_at_import")).isEqualTo("America/Mexico_City");
        assertThat(row.get("sub_type")).isEqualTo("ROAD");
        assertThat(((Number) row.get("moving_time_seconds")).doubleValue())
                .as("prefiere el dato del dispositivo")
                .isEqualTo(5.0);
        assertThat(row.get("moving_source")).isEqualTo("DEVICE_REPORTED");
        assertThat(row.get("distance_source")).isEqualTo("DERIVED");
        assertThat(row.get("average_heart_rate_bpm"))
                .as("métrica ausente permanece NULL")
                .isNull();
    }

    @Test
    void keepsDeviceAndDerivedMetricsSeparately() {
        var id = registration.register(activity("Rodada nocturna", sha(2)));

        var moving = jdbc.sql("""
                        SELECT source, value, algorithm_version, parameters ->> 'movingSpeedThresholdMps'
                        FROM activity_metrics WHERE activity_id = :id AND metric = 'MOVING_TIME' ORDER BY source
                        """)
                .param("id", id)
                .query((rs, n) -> rs.getString(1) + "="
                        + rs.getBigDecimal(2).stripTrailingZeros().toPlainString() + "/" + rs.getString(3) + "/"
                        + rs.getString(4))
                .list();
        assertThat(moving).containsExactly("DERIVED=4/derived-metrics/1/1.0", "DEVICE_REPORTED=5/null/null");
    }

    @Test
    void storesTrackAsMultiLineStringWithOneLinePerSegment() {
        var id = registration.register(activity("X", sha(3)));

        var track = jdbc.sql("""
                        SELECT GeometryType(route_geom), ST_NumGeometries(route_geom), point_count,
                               ST_X(start_point), ST_Y(start_point), ST_Y(end_point), GeometryType(bbox), ST_SRID(route_geom)
                        FROM activity_tracks WHERE activity_id = :id
                        """).param("id", id).query().singleRow();
        assertThat(track.get("geometrytype")).isEqualTo("MULTILINESTRING");
        assertThat(track.get("st_numgeometries")).isEqualTo(2);
        assertThat(track.get("point_count")).isEqualTo(5);
        assertThat((Double) track.get("st_x")).isCloseTo(-99.0, within(1e-9));
        assertThat((Double) track.get("st_y")).isCloseTo(19.0, within(1e-9));
        assertThat(track.get("st_srid")).isEqualTo(4326);
    }

    @Test
    void storesOnlyStreamsWithDataAndTheyRoundTrip() {
        var id = registration.register(activity("X", sha(4)));

        var types = jdbc.sql("SELECT stream_type FROM activity_streams WHERE activity_id = :id ORDER BY stream_type")
                .param("id", id)
                .query(String.class)
                .list();
        assertThat(types).containsExactly("ALTITUDE", "LATITUDE", "LONGITUDE", "TIME");

        var altitude = jdbc.sql(
                        "SELECT payload FROM activity_streams WHERE activity_id = :id AND stream_type = 'ALTITUDE'")
                .param("id", id)
                .query(byte[].class)
                .single();
        assertThat(StreamCodec.decode(altitude, StreamType.ALTITUDE.scale()))
                .containsExactly(2_000.0, 2_000.5, null, 2_010.0, 2_010.2, 2_010.4);
    }

    @Test
    void indoorActivityHasNoTrack() {
        var indoor = new NewActivity.Samples(
                2,
                new int[] {0, 0},
                Map.of(StreamType.TIME, new Double[] {0.0, 1_000.0}, StreamType.POWER, new Double[] {200.0, 210.0}));
        var cmd = activity("Rodillo", sha(5));
        var id = registration.register(new NewActivity(
                userId,
                null,
                null,
                cmd.title(),
                ActivitySubType.INDOOR,
                START,
                MEXICO_CITY,
                "FIT",
                null,
                metrics(),
                indoor,
                List.of(),
                cmd.file()));

        assertThat(jdbc.sql("SELECT count(*) FROM activity_tracks WHERE activity_id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void rejectsTheSameOriginalTwiceAndReportsTheExistingActivity() {
        var first = registration.register(activity("X", sha(6)));

        assertThatThrownBy(() -> registration.register(activity("Y", sha(6))))
                .isInstanceOfSatisfying(
                        ActivityRegistration.DuplicateOriginalException.class,
                        e -> assertThat(e.existingActivityId()).isEqualTo(first));
        assertThat(jdbc.sql("SELECT count(*) FROM activities")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void deletingTheUserDeletesEverything() {
        registration.register(activity("X", sha(7)));

        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", userId).update();

        for (var table : List.of(
                "activities",
                "activity_metrics",
                "activity_tracks",
                "activity_laps",
                "activity_streams",
                "activity_files")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table)
                            .query(Integer.class)
                            .single())
                    .as(table)
                    .isZero();
        }
    }

    @Test
    void requiresElapsedAndMovingTime() {
        var cmd = activity("X", sha(8));
        var incomplete = new NewActivity(
                userId,
                null,
                null,
                "X",
                ActivitySubType.ROAD,
                START,
                MEXICO_CITY,
                "GPX",
                null,
                List.of(new MetricValue(Metric.DISTANCE, Source.DERIVED, 10, "derived-metrics/1", null)),
                cmd.samples(),
                List.of(),
                cmd.file());

        assertThatThrownBy(() -> registration.register(incomplete)).isInstanceOf(IllegalArgumentException.class);
    }
}
