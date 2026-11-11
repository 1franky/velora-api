package mx.velora.api.geospatial.exploration;

import com.uber.h3core.H3Core;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calcula las celdas de exploración de una actividad (ADR-018): H3 de resolución 9 y la cuadrícula métrica de 1 km
 * (solo México), a partir del recorrido ya recortado por la privacidad de inicio y fin vigente. Muestrea el camino
 * cada {@value #SAMPLE_METERS} m (sin unir tramos separados por pausas). Reemplaza el cálculo anterior.
 */
@Service
class ExplorationCells {

    static final String ALGORITHM_VERSION = "exploration-cells/1";
    static final int H3_RESOLUTION = 9;
    static final int SAMPLE_METERS = 20;

    /** Muestra del camino: posición y, dentro de México, la celda de 1 km (o {@code null}). */
    private record Sample(
            double lat,
            double lon,
            @Nullable Integer col,
            @Nullable Integer row) {}

    private record Source(UUID userId, int privacyMeters) {}

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final Clock clock;
    private final H3Core h3;

    ExplorationCells(JdbcClient jdbc, JdbcTemplate template, Clock clock) {
        this.jdbc = jdbc;
        this.template = template;
        this.clock = clock;
        try {
            this.h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo cargar la biblioteca nativa de H3", e);
        }
    }

    /**
     * Calcula y guarda las celdas. Si la actividad ya no existe o no tiene recorrido, no hace nada. Un bloqueo por
     * actividad evita que el listener y la puesta al día escriban a la vez, y {@code FOR SHARE} hace que un borrado
     * simultáneo espere a que termine (si no, las celdas apuntarían a una actividad que ya no existe). Transacción propia: en una puesta al día
     * larga, cada actividad queda guardada en cuanto termina.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compute(UUID activityId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:id::text, 0))")
                .param("id", activityId)
                .query()
                .singleRow();
        // Mismo orden de bloqueos que el borrado en cascada (usuario y luego actividad): sin bloqueos mutuos.
        jdbc.sql("""
                        SELECT 1 FROM users u JOIN activities a ON a.user_id = u.id
                        WHERE a.id = :id FOR KEY SHARE OF u""").param("id", activityId).query().listOfRows();
        var source = jdbc.sql("""
                        SELECT a.user_id, u.track_privacy_meters
                        FROM activities a
                        JOIN users u ON u.id = a.user_id
                        JOIN activity_tracks t ON t.activity_id = a.id
                        WHERE a.id = :id
                        FOR SHARE OF a""")
                .param("id", activityId)
                .query((rs, n) -> new Source(rs.getObject("user_id", UUID.class), rs.getInt("track_privacy_meters")))
                .optional();
        if (source.isEmpty()) {
            return;
        }
        var samples = samples(activityId, source.get().privacyMeters());
        var hexagons = new HashSet<Long>();
        var squares = new HashSet<Long>();
        for (var sample : samples) {
            hexagons.add(h3.latLngToCell(sample.lat(), sample.lon(), H3_RESOLUTION));
            if (sample.col() != null && sample.row() != null) {
                squares.add(new MetricCell(sample.col(), sample.row()).encode());
            }
        }
        jdbc.sql("DELETE FROM exploration_cells WHERE activity_id = :id")
                .param("id", activityId)
                .update();
        insert(activityId, source.get().userId(), "H3", H3_RESOLUTION, hexagons);
        insert(activityId, source.get().userId(), "MX1KM", MetricCell.SIZE_METERS, squares);
        jdbc.sql("""
                        INSERT INTO exploration_runs
                            (activity_id, user_id, algorithm_version, parameters, privacy_meters, computed_at)
                        VALUES (:id, :user, :version, :parameters::jsonb, :privacy, :now)
                        ON CONFLICT (activity_id) DO UPDATE SET
                            algorithm_version = excluded.algorithm_version, parameters = excluded.parameters,
                            privacy_meters = excluded.privacy_meters, computed_at = excluded.computed_at""")
                .param("id", activityId)
                .param("user", source.get().userId())
                .param("version", ALGORITHM_VERSION)
                .param(
                        "parameters",
                        "{\"h3Resolution\":%d,\"metricGridMeters\":%d,\"sampleMeters\":%d}"
                                .formatted(H3_RESOLUTION, MetricCell.SIZE_METERS, SAMPLE_METERS))
                .param("privacy", source.get().privacyMeters())
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    /** Actividades con recorrido sin calcular, con otra versión del algoritmo o con otra privacidad. */
    public List<UUID> outdated(@Nullable UUID userId, int limit) {
        return jdbc.sql("""
                        SELECT a.id
                        FROM activities a
                        JOIN activity_tracks t ON t.activity_id = a.id
                        JOIN users u ON u.id = a.user_id
                        LEFT JOIN exploration_runs r ON r.activity_id = a.id
                        WHERE (CAST(:user AS uuid) IS NULL OR a.user_id = :user)
                          AND (r.activity_id IS NULL OR r.algorithm_version <> :version
                               OR r.privacy_meters <> u.track_privacy_meters)
                        ORDER BY a.started_at DESC
                        LIMIT :limit""")
                .param("user", userId, java.sql.Types.OTHER)
                .param("version", ALGORITHM_VERSION)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    private List<Sample> samples(UUID activityId, int privacyMeters) {
        return jdbc.sql("""
                        WITH route AS (
                            SELECT CASE WHEN :privacy > 0
                                        THEN velora_trim_route(route_geom,
                                                               :privacy * velora_privacy_factor(activity_id, true),
                                                               :privacy * velora_privacy_factor(activity_id, false))
                                        ELSE route_geom END AS g
                            FROM activity_tracks WHERE activity_id = :id),
                        points AS (
                            SELECT (ST_DumpPoints(ST_Segmentize(g::geography, :spacing)::geometry)).geom AS p
                            FROM route WHERE g IS NOT NULL),
                        projected AS (
                            SELECT p, CASE WHEN p && ST_MakeEnvelope(-118.6, 14.3, -86.5, 32.8, 4326)
                                           THEN ST_Transform(p, 6372) END AS m
                            FROM points)
                        SELECT ST_Y(p) AS lat, ST_X(p) AS lon,
                               floor(ST_X(m) / :size)::int AS col, floor(ST_Y(m) / :size)::int AS row
                        FROM projected""")
                .param("id", activityId)
                .param("privacy", privacyMeters)
                .param("spacing", (double) SAMPLE_METERS)
                .param("size", (double) MetricCell.SIZE_METERS)
                .query((rs, n) ->
                        new Sample(rs.getDouble("lat"), rs.getDouble("lon"), (Integer) rs.getObject("col"), (Integer)
                                rs.getObject("row")))
                .list();
    }

    private void insert(UUID activityId, UUID userId, String grid, int resolution, Set<Long> cells) {
        if (cells.isEmpty()) {
            return;
        }
        template.update(connection -> {
            var statement = connection.prepareStatement("""
                            INSERT INTO exploration_cells (activity_id, user_id, grid, resolution, cell)
                            SELECT ?, ?, ?, ?, unnest(?::bigint[])
                            ON CONFLICT DO NOTHING""");
            statement.setObject(1, activityId);
            statement.setObject(2, userId);
            statement.setString(3, grid);
            statement.setInt(4, resolution);
            statement.setArray(5, connection.createArrayOf("bigint", cells.toArray()));
            return statement;
        });
    }
}
