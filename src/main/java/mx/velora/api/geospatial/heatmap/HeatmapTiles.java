package mx.velora.api.geospatial.heatmap;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.UUID;
import mx.velora.api.activity.ActivitySubType;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Teselas vectoriales (MVT) del mapa de calor personal (ADR-017). Cada recorrido es una línea; el cliente las dibuja
 * translúcidas y la intensidad sale de cuántas se enciman. Solo lectura sobre las tablas del módulo activity.
 */
@Service
class HeatmapTiles {

    /** Nombre de la capa dentro de la tesela. */
    static final String LAYER = "recorridos";

    static final int EXTENT = 4096;
    static final int BUFFER = 64;
    private static final double WEB_MERCATOR_WIDTH_METERS = 40_075_016.685_578_5;

    record Filter(
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable UUID bikeId,
            @Nullable ActivitySubType subType) {}

    private final JdbcClient jdbc;

    HeatmapTiles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tesela {@code z/x/y} con los recorridos del usuario que pasan por ella, ya recortados según la privacidad de
     * inicio y fin ({@code privacyMeters} = 0 la desactiva) y simplificados a medio píxel de la tesela. Vacía (0
     * bytes) si no hay nada que dibujar; el controlador responde entonces 204.
     */
    byte[] tile(UUID userId, int z, int x, int y, Filter filter, int privacyMeters) {
        var sql = new StringBuilder("""
                WITH bounds AS (SELECT ST_TileEnvelope(:z, :x, :y) AS env,
                                       ST_Transform(ST_TileEnvelope(:z, :x, :y, margin => :margin), 4326) AS search),
                     src AS (
                         SELECT CASE WHEN :privacy > 0
                                     THEN velora_trim_route(t.route_geom,
                                                            :privacy * velora_privacy_factor(a.id, true),
                                                            :privacy * velora_privacy_factor(a.id, false))
                                     ELSE t.route_geom END AS g
                         FROM activities a
                         JOIN activity_tracks t ON t.activity_id = a.id, bounds
                         WHERE a.user_id = :user AND t.route_geom && bounds.search""");
        var params = new HashMap<String, Object>();
        params.put("user", userId);
        params.put("z", z);
        params.put("x", x);
        params.put("y", y);
        params.put("margin", (double) BUFFER / EXTENT);
        params.put("privacy", privacyMeters);
        params.put("tolerance", WEB_MERCATOR_WIDTH_METERS / Math.pow(2, z) / EXTENT / 2);
        if (filter.from() != null) {
            sql.append(" AND a.local_start_date >= :from");
            params.put("from", Date.valueOf(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND a.local_start_date <= :to");
            params.put("to", Date.valueOf(filter.to()));
        }
        if (filter.bikeId() != null) {
            sql.append(" AND a.bike_id = :bike");
            params.put("bike", filter.bikeId());
        }
        if (filter.subType() != null) {
            sql.append(" AND a.sub_type = :subType");
            params.put("subType", filter.subType().name());
        }
        sql.append("""
                     ),
                     features AS (
                         SELECT ST_AsMVTGeom(ST_Simplify(ST_Transform(src.g, 3857), :tolerance), bounds.env, :extent, :buffer, true)
                                    AS geom
                         FROM src, bounds
                         WHERE src.g IS NOT NULL
                     )
                SELECT coalesce(ST_AsMVT(features, :layer, :extent, 'geom'), ''::bytea)
                FROM features WHERE geom IS NOT NULL""");
        params.put("extent", EXTENT);
        params.put("buffer", BUFFER);
        params.put("layer", LAYER);
        var bytes = jdbc.sql(sql.toString()).params(params).query(byte[].class).single();
        return bytes == null ? new byte[0] : bytes;
    }
}
