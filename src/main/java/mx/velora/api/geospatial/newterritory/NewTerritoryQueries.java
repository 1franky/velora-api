package mx.velora.api.geospatial.newterritory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lee el recorrido de una actividad (recortado por la privacidad, ADR-016) muestreado cada {@value #SAMPLE_METERS} m
 * y marca cada muestra según las rodadas anteriores del mismo usuario (recorridos completos, en trozos: sí pasaste por
 * ahí).
 */
@Service
class NewTerritoryQueries {

    static final int SAMPLE_METERS = 10;
    private static final double METERS_PER_DEGREE = 111_320;

    /** Datos de la actividad: privacidad aplicada, densidad del GPS y cuántas rodadas anteriores hay. */
    record Subject(int privacyMeters, double sampleSpacingMeters, long earlierActivities) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    NewTerritoryQueries(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
    }

    /** Vacío si la actividad no existe, es de otro usuario o no tiene recorrido. */
    Optional<Subject> subject(UUID userId, UUID activityId) {
        return jdbc.sql("""
                        SELECT u.track_privacy_meters AS privacy,
                               ST_Length(t.route_geom::geography) / greatest(t.point_count - 1, 1) AS spacing,
                               (SELECT count(*) FROM activities o JOIN activity_tracks ot ON ot.activity_id = o.id
                                WHERE o.user_id = a.user_id AND o.started_at < a.started_at) AS earlier
                        FROM activities a
                        JOIN activity_tracks t ON t.activity_id = a.id
                        JOIN users u ON u.id = a.user_id
                        WHERE a.id = :id AND a.user_id = :user""")
                .param("id", activityId)
                .param("user", userId)
                .query((rs, n) -> new Subject(rs.getInt("privacy"), rs.getDouble("spacing"), rs.getLong("earlier")))
                .optional();
    }

    /**
     * Muestras en orden. Una muestra es nueva si ninguna rodada que empezó antes pasa a menos de
     * {@code toleranceMeters}. Se compara contra los trozos del recorrido de las demás rodadas
     * ({@code activity_route_chunks}, ADR-031): el índice GiST deja solo los que pasan cerca de cada muestra, y la
     * distancia se mide en metros planos con una escala local (equirectangular) en la latitud del recorrido.
     *
     * <p>Sin JIT: el plan parece caro (miles de búsquedas en el índice) y PostgreSQL gastaría ~0.4 s compilándolo para
     * una consulta que tarda menos que eso.
     */
    List<NewTerritory.Sample> samples(UUID activityId, int privacyMeters, double toleranceMeters) {
        return transactions.execute(status -> {
            jdbc.sql("SET LOCAL jit = off").update();
            return query(activityId, privacyMeters, toleranceMeters);
        });
    }

    private List<NewTerritory.Sample> query(UUID activityId, int privacyMeters, double toleranceMeters) {
        return jdbc.sql("""
                        WITH me AS (
                            SELECT a.id, a.user_id, a.started_at,
                                   CASE WHEN :privacy > 0
                                        THEN velora_trim_route(t.route_geom,
                                                               :privacy * velora_privacy_factor(a.id, true),
                                                               :privacy * velora_privacy_factor(a.id, false))
                                        ELSE t.route_geom END AS g,
                                   radians(ST_Y(ST_Centroid(t.bbox))) AS phi
                            FROM activities a JOIN activity_tracks t ON t.activity_id = a.id
                            WHERE a.id = :id),
                        -- Metros por grado en la latitud del recorrido: escala equirectangular local. Sin PROJ (lento
                        -- la primera vez en cada conexión) y con error despreciable a la escala de una rodada.
                        scale AS (
                            SELECT 111412.84 * cos(phi) - 93.5 * cos(3 * phi) AS kx,
                                   111132.954 - 559.822 * cos(2 * phi) + 1.175 * cos(4 * phi) AS ky
                            FROM me),
                        parts AS (
                            SELECT (d).path[1] AS part, (d).geom AS line
                            FROM (SELECT ST_Dump(g) AS d FROM me WHERE g IS NOT NULL) s),
                        points AS (
                            SELECT part, (dp).path[1] AS idx, (dp).geom AS p
                            FROM (SELECT part, ST_DumpPoints(ST_Segmentize(line::geography, :spacing)::geometry) AS dp
                                  FROM parts) s),
                        -- Materializado: con las muestras ya calculadas, cada búsqueda de abajo usa el índice.
                        flagged AS MATERIALIZED (
                            SELECT pt.part, pt.idx, pt.p, ST_Scale(pt.p, scale.kx, scale.ky) AS m, scale.kx, scale.ky,
                                   me.user_id, me.started_at
                            FROM points pt, scale, me)
                        SELECT f.part, ST_Y(f.p) AS lat, ST_X(f.p) AS lon,
                               known.hit IS NULL AS fresh,
                               known.hit IS NULL AND close.hit IS NOT NULL AS near
                        FROM flagged f
                        LEFT JOIN LATERAL (
                            SELECT 1 AS hit
                            FROM activity_route_chunks c JOIN activities o ON o.id = c.activity_id
                            WHERE c.geom && ST_Expand(f.p, :margin)
                              AND o.user_id = f.user_id AND o.started_at < f.started_at
                              AND ST_DWithin(ST_Scale(c.geom, f.kx, f.ky), f.m, :tolerance)
                            LIMIT 1) known ON true
                        -- Solo para las nuevas: ¿alguna rodada pasa a menos del doble? (resultado dudoso)
                        LEFT JOIN LATERAL (
                            SELECT 1 AS hit
                            FROM activity_route_chunks c JOIN activities o ON o.id = c.activity_id
                            WHERE known.hit IS NULL
                              AND c.geom && ST_Expand(f.p, :margin)
                              AND o.user_id = f.user_id AND o.started_at < f.started_at
                              AND ST_DWithin(ST_Scale(c.geom, f.kx, f.ky), f.m, 2 * :tolerance)
                            LIMIT 1) close ON true
                        ORDER BY f.part, f.idx""")
                .param("id", activityId)
                .param("privacy", privacyMeters)
                .param("spacing", (double) SAMPLE_METERS)
                .param("tolerance", toleranceMeters)
                // Recuadro de la muestra ampliado ~3 tolerancias (en grados, con holgura para la longitud).
                .param("margin", 3 * toleranceMeters / METERS_PER_DEGREE)
                .query((rs, n) -> new NewTerritory.Sample(
                        rs.getInt("part"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon"),
                        rs.getBoolean("fresh"),
                        rs.getBoolean("near")))
                .list();
    }
}
