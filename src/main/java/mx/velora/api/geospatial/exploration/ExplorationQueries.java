package mx.velora.api.geospatial.exploration;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Lecturas de las celdas de exploración (ADR-018). Solo devuelve celdas ya recortadas por la privacidad. */
@Service
class ExplorationQueries {

    record Counts(long processed, long pending, long hexagons, long squares) {}

    /** Celda lista para dibujar: polígono GeoJSON (texto) y cuántas actividades pasaron por ella. */
    record Cell(String geometryJson, long activityCount) {}

    record Bbox(double west, double south, double east, double north) {}

    private final JdbcClient jdbc;
    private final H3Core h3;

    ExplorationQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
        try {
            this.h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo cargar la biblioteca nativa de H3", e);
        }
    }

    Counts counts(UUID userId) {
        return jdbc.sql("""
                        SELECT
                          (SELECT count(*) FROM exploration_runs r JOIN users u ON u.id = r.user_id
                           WHERE r.user_id = :user AND r.algorithm_version = :version
                             AND r.privacy_meters = u.track_privacy_meters) AS processed,
                          (SELECT count(*) FROM activities a
                           JOIN activity_tracks t ON t.activity_id = a.id
                           JOIN users u ON u.id = a.user_id
                           LEFT JOIN exploration_runs r ON r.activity_id = a.id
                           WHERE a.user_id = :user
                             AND (r.activity_id IS NULL OR r.algorithm_version <> :version
                                  OR r.privacy_meters <> u.track_privacy_meters)) AS pending,
                          (SELECT count(DISTINCT cell) FROM exploration_cells
                           WHERE user_id = :user AND grid = 'H3') AS hexagons,
                          (SELECT count(DISTINCT cell) FROM exploration_cells
                           WHERE user_id = :user AND grid = 'MX1KM') AS squares""")
                .param("user", userId)
                .param("version", ExplorationCells.ALGORITHM_VERSION)
                .query((rs, n) -> new Counts(
                        rs.getLong("processed"), rs.getLong("pending"), rs.getLong("hexagons"), rs.getLong("squares")))
                .single();
    }

    double hexagonAreaKm2() {
        return h3.getHexagonAreaAvg(ExplorationCells.H3_RESOLUTION, com.uber.h3core.AreaUnit.km2);
    }

    /** Hasta {@code limit} celdas que tocan el recuadro; una más indica que hay más. */
    List<Cell> cells(UUID userId, String grid, Bbox bbox, int limit) {
        return "H3".equals(grid) ? hexagons(userId, bbox, limit) : squares(userId, bbox, limit);
    }

    private List<Cell> squares(UUID userId, Bbox bbox, int limit) {
        return jdbc.sql("""
                        WITH area AS (SELECT ST_Transform(ST_MakeEnvelope(:w, :s, :e, :n, 4326), 6372) AS env),
                             visited AS (
                                 SELECT cell, count(*) AS activities FROM exploration_cells
                                 WHERE user_id = :user AND grid = 'MX1KM' GROUP BY cell),
                             squares AS (
                                 SELECT activities, ST_MakeEnvelope((cell >> 20) * :size, (cell & 1048575) * :size,
                                                                    ((cell >> 20) + 1) * :size,
                                                                    ((cell & 1048575) + 1) * :size, 6372) AS sq
                                 FROM visited)
                        SELECT ST_AsGeoJSON(ST_Transform(sq, 4326), 6) AS geometry, activities
                        FROM squares, area WHERE sq && area.env
                        ORDER BY activities DESC
                        LIMIT :limit""")
                .param("user", userId)
                .param("w", bbox.west())
                .param("s", bbox.south())
                .param("e", bbox.east())
                .param("n", bbox.north())
                .param("size", (double) MetricCell.SIZE_METERS)
                .param("limit", limit + 1)
                .query((rs, n) -> new Cell(rs.getString("geometry"), rs.getLong("activities")))
                .list();
    }

    private List<Cell> hexagons(UUID userId, Bbox bbox, int limit) {
        var visited = jdbc.sql("""
                        SELECT cell, count(*) AS activities FROM exploration_cells
                        WHERE user_id = :user AND grid = 'H3' GROUP BY cell ORDER BY activities DESC""")
                .param("user", userId)
                .query((rs, n) -> new long[] {rs.getLong("cell"), rs.getLong("activities")})
                .list();
        // Margen de medio hexágono (~200 m) para no recortar los que asoman por el borde.
        var margin = 0.002;
        var cells = new ArrayList<Cell>();
        for (var row : visited) {
            var center = h3.cellToLatLng(row[0]);
            if (center.lat < bbox.south() - margin
                    || center.lat > bbox.north() + margin
                    || center.lng < bbox.west() - margin
                    || center.lng > bbox.east() + margin) {
                continue;
            }
            cells.add(new Cell(polygon(h3.cellToBoundary(row[0])), row[1]));
            if (cells.size() > limit) {
                break;
            }
        }
        return cells;
    }

    /** Mayor cuadrado de la cuadrícula de 1 km y su recuadro en grados; vacío si no hay celdas. */
    Optional<LargestSquareResult> largestSquare(UUID userId) {
        var visited = jdbc
                .sql("SELECT DISTINCT cell FROM exploration_cells WHERE user_id = :user AND grid = 'MX1KM'")
                .param("user", userId)
                .query(Long.class)
                .list()
                .stream()
                .map(MetricCell::decode)
                .toList();
        return LargestSquare.of(visited).map(square -> {
            var size = MetricCell.SIZE_METERS;
            var bbox = jdbc.sql("""
                            WITH g AS (SELECT ST_Transform(ST_MakeEnvelope(:x0, :y0, :x1, :y1, 6372), 4326) AS env)
                            SELECT ST_XMin(env) AS w, ST_YMin(env) AS s, ST_XMax(env) AS e, ST_YMax(env) AS n
                            FROM g""")
                    .param("x0", (double) square.col() * size)
                    .param("y0", (double) square.row() * size)
                    .param("x1", (double) (square.col() + square.size()) * size)
                    .param("y1", (double) (square.row() + square.size()) * size)
                    .query((rs, n) ->
                            new Bbox(rs.getDouble("w"), rs.getDouble("s"), rs.getDouble("e"), rs.getDouble("n")))
                    .single();
            return new LargestSquareResult(square.size(), bbox);
        });
    }

    record LargestSquareResult(int size, Bbox bbox) {}

    private static String polygon(List<LatLng> boundary) {
        var json = new StringBuilder("{\"type\":\"Polygon\",\"coordinates\":[[");
        for (var point : boundary) {
            json.append(String.format(Locale.ROOT, "[%.6f,%.6f],", point.lng, point.lat));
        }
        var first = boundary.getFirst();
        json.append(String.format(Locale.ROOT, "[%.6f,%.6f]]]}", first.lng, first.lat));
        return json.toString();
    }
}
