package mx.velora.api.geospatial.exploration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Optional;

/**
 * Mayor cuadrado de celdas visitadas contiguas (04-geospatial-and-analytics.md §5): cuadrícula métrica de 1 km
 * alineada al origen de EPSG:6372, conectividad por lados, una celda faltante rompe el cuadrado. Programación
 * dinámica clásica, pero solo sobre las celdas visitadas (las demás valen 0): O(n log n) con n celdas, sin
 * materializar la matriz del país.
 */
final class LargestSquare {

    static final String ALGORITHM_VERSION = "largest-square/1";

    /** Cuadrado de {@code size} × {@code size} celdas cuya esquina inferior izquierda es {@code (col, row)}. */
    record Square(int col, int row, int size) {}

    private LargestSquare() {}

    static Optional<Square> of(Collection<MetricCell> visited) {
        var cells = new ArrayList<>(visited);
        cells.sort(Comparator.comparingInt(MetricCell::row).thenComparingInt(MetricCell::col));
        var sizes = new HashMap<MetricCell, Integer>(cells.size() * 2);
        Square best = null;
        for (var cell : cells) {
            // Esquina superior derecha del cuadrado: crece si las tres vecinas (izquierda, abajo, diagonal) también.
            var left = sizes.getOrDefault(new MetricCell(cell.col() - 1, cell.row()), 0);
            var below = sizes.getOrDefault(new MetricCell(cell.col(), cell.row() - 1), 0);
            var diagonal = sizes.getOrDefault(new MetricCell(cell.col() - 1, cell.row() - 1), 0);
            var size = 1 + Math.min(left, Math.min(below, diagonal));
            sizes.put(cell, size);
            if (best == null || size > best.size() || (size == best.size() && cell.row() - size + 1 < best.row())) {
                best = new Square(cell.col() - size + 1, cell.row() - size + 1, size);
            }
        }
        return Optional.ofNullable(best);
    }
}
