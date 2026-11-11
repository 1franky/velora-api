package mx.velora.api.geospatial.newterritory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Lee el recorrido de una actividad (recortado por la privacidad, ADR-016) muestreado cada {@value #SAMPLE_METERS} m
 * y marca cada muestra según las rodadas anteriores del mismo usuario (recorridos completos: sí pasaste por ahí).
 */
@Service
class NewTerritoryQueries {

    static final int SAMPLE_METERS = 10;
    private static final double METERS_PER_DEGREE = 111_320;

    /** Datos de la actividad: privacidad aplicada, densidad del GPS y cuántas rodadas anteriores hay. */
    record Subject(int privacyMeters, double sampleSpacingMeters, long earlierActivities) {}

    private final JdbcClient jdbc;

    NewTerritoryQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
     * {@code toleranceMeters}. El índice GiST deja solo las rodadas cuyo recuadro toca el de esta, y la distancia se mide
     * en metros sobre la zona UTM del recorrido.
     */
    List<NewTerritory.Sample> samples(UUID activityId, int privacyMeters, double toleranceMeters) {
        return jdbc.sql("""
                        WITH me AS (
                            SELECT a.id, a.user_id, a.started_at,
                                   CASE WHEN :privacy > 0
                                        THEN velora_trim_route(t.route_geom,
                                                               :privacy * velora_privacy_factor(a.id, true),
                                                               :privacy * velora_privacy_factor(a.id, false))
                                        ELSE t.route_geom END AS g,
                                   -- Zona UTM del centro del recorrido: distancias planas en metros, mucho más
                                   -- rápidas que sobre el geoide y con error despreciable a escala de una rodada.
                                   CASE WHEN ST_Y(ST_Centroid(t.bbox)) >= 0 THEN 32600 ELSE 32700 END
                                       + least(60, floor((ST_X(ST_Centroid(t.bbox)) + 180) / 6)::int + 1) AS srid
                            FROM activities a JOIN activity_tracks t ON t.activity_id = a.id
                            WHERE a.id = :id),
                        earlier AS MATERIALIZED (
                            SELECT ST_Transform(ot.route_geom, me.srid) AS g
                            FROM activities o JOIN activity_tracks ot ON ot.activity_id = o.id, me
                            WHERE o.user_id = me.user_id AND o.started_at < me.started_at AND me.g IS NOT NULL
                              AND ot.route_geom && ST_Expand(me.g, :margin)),
                        parts AS (
                            SELECT (d).path[1] AS part, (d).geom AS line
                            FROM (SELECT ST_Dump(g) AS d FROM me WHERE g IS NOT NULL) s),
                        points AS (
                            SELECT part, (dp).path[1] AS idx, (dp).geom AS p
                            FROM (SELECT part, ST_DumpPoints(ST_Segmentize(line::geography, :spacing)::geometry) AS dp
                                  FROM parts) s),
                        flagged AS (
                            SELECT pt.part, pt.idx, pt.p, ST_Transform(pt.p, me.srid) AS m
                            FROM points pt, me)
                        SELECT part, ST_Y(p) AS lat, ST_X(p) AS lon,
                               NOT EXISTS (SELECT 1 FROM earlier e WHERE ST_DWithin(e.g, m, :tolerance)) AS fresh,
                               (NOT EXISTS (SELECT 1 FROM earlier e WHERE ST_DWithin(e.g, m, :tolerance))
                                AND EXISTS (SELECT 1 FROM earlier e WHERE ST_DWithin(e.g, m, 2 * :tolerance))) AS near
                        FROM flagged
                        ORDER BY part, idx""")
                .param("id", activityId)
                .param("privacy", privacyMeters)
                .param("spacing", (double) SAMPLE_METERS)
                .param("tolerance", toleranceMeters)
                // Recuadro del recorrido ampliado ~2 tolerancias (en grados, con holgura para la latitud).
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
