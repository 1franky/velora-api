package mx.velora.api.geospatial.exploration;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.util.List;
import java.util.Locale;
import mx.velora.api.identity.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/geo/exploration/*} según el contrato (ADR-018). */
@RestController
@RequestMapping("/api/v1/geo/exploration")
class ExplorationController {

    /** Máximo de celdas por respuesta; con más, {@code truncated} y el cliente debe acercarse. */
    static final int MAX_CELLS = 5_000;

    record HexagonGrid(int resolution, long cellCount, double approxAreaKm2) {}

    record MetricGrid(int cellSizeMeters, long cellCount, double areaKm2) {}

    record Summary(
            String algorithmVersion,
            long processedActivities,
            long pendingActivities,
            HexagonGrid h3,
            MetricGrid metricGrid) {}

    record CellProperties(long activityCount) {}

    record CellFeature(String type, @JsonRawValue String geometry, CellProperties properties) {}

    record CellCollection(String type, String grid, boolean truncated, List<CellFeature> features) {}

    record Square(String algorithmVersion, int cellSizeMeters, int size, double sideKm, double @Nullable [] bbox) {}

    private final ExplorationQueries queries;

    ExplorationController(ExplorationQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/summary")
    Summary summary(@AuthenticationPrincipal AuthenticatedUser user) {
        var counts = queries.counts(user.userId());
        var hexArea = queries.hexagonAreaKm2();
        return new Summary(
                ExplorationCells.ALGORITHM_VERSION,
                counts.processed(),
                counts.pending(),
                new HexagonGrid(
                        ExplorationCells.H3_RESOLUTION,
                        counts.hexagons(),
                        Math.round(counts.hexagons() * hexArea * 100) / 100.0),
                new MetricGrid(
                        MetricCell.SIZE_METERS,
                        counts.squares(),
                        counts.squares() * Math.pow(MetricCell.SIZE_METERS / 1000.0, 2)));
    }

    @GetMapping(value = "/cells", produces = "application/geo+json")
    CellCollection cells(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "MX1KM") String grid,
            @RequestParam String bbox) {
        var normalized = grid.toUpperCase(Locale.ROOT);
        if (!normalized.equals("H3") && !normalized.equals("MX1KM")) {
            throw new InvalidExplorationQueryException();
        }
        var cells = queries.cells(user.userId(), normalized, parseBbox(bbox), MAX_CELLS);
        var truncated = cells.size() > MAX_CELLS;
        var features = cells.stream()
                .limit(MAX_CELLS)
                .map(cell -> new CellFeature("Feature", cell.geometryJson(), new CellProperties(cell.activityCount())))
                .toList();
        return new CellCollection("FeatureCollection", normalized, truncated, features);
    }

    @GetMapping("/largest-square")
    Square largestSquare(@AuthenticationPrincipal AuthenticatedUser user) {
        var side = MetricCell.SIZE_METERS / 1000.0;
        return queries.largestSquare(user.userId())
                .map(found -> new Square(
                        LargestSquare.ALGORITHM_VERSION,
                        MetricCell.SIZE_METERS,
                        found.size(),
                        found.size() * side,
                        new double[] {
                            found.bbox().west(),
                            found.bbox().south(),
                            found.bbox().east(),
                            found.bbox().north()
                        }))
                .orElseGet(() -> new Square(LargestSquare.ALGORITHM_VERSION, MetricCell.SIZE_METERS, 0, 0, null));
    }

    /** {@code oeste,sur,este,norte} en grados, dentro de rango y con oeste < este y sur < norte. */
    static ExplorationQueries.Bbox parseBbox(String value) {
        var parts = value.split(",");
        if (parts.length != 4) {
            throw new InvalidExplorationQueryException();
        }
        try {
            var west = Double.parseDouble(parts[0].strip());
            var south = Double.parseDouble(parts[1].strip());
            var east = Double.parseDouble(parts[2].strip());
            var north = Double.parseDouble(parts[3].strip());
            if (!(west >= -180 && east <= 180 && south >= -90 && north <= 90 && west < east && south < north)) {
                throw new InvalidExplorationQueryException();
            }
            return new ExplorationQueries.Bbox(west, south, east, north);
        } catch (NumberFormatException e) {
            throw new InvalidExplorationQueryException();
        }
    }

    static final class InvalidExplorationQueryException extends ErrorResponseException {
        InvalidExplorationQueryException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }
}
