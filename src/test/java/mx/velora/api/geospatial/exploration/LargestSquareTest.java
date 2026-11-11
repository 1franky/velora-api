package mx.velora.api.geospatial.exploration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LargestSquareTest {

    private static List<MetricCell> block(int col, int row, int size) {
        var cells = new ArrayList<MetricCell>();
        for (int c = col; c < col + size; c++) {
            for (int r = row; r < row + size; r++) {
                cells.add(new MetricCell(c, r));
            }
        }
        return cells;
    }

    @Test
    void noCellsNoSquare() {
        assertThat(LargestSquare.of(List.of())).isEmpty();
    }

    @Test
    void aSingleCellIsASquareOfOne() {
        assertThat(LargestSquare.of(List.of(new MetricCell(4_000, 1_200))))
                .contains(new LargestSquare.Square(4_000, 1_200, 1));
    }

    @Test
    void findsTheBiggestFullBlockAndIgnoresAHoleElsewhere() {
        var cells = new ArrayList<>(block(100, 100, 4));
        var withHole = block(200, 200, 6);
        withHole.remove(new MetricCell(202, 203)); // un hueco: el 6×6 ya no cuenta
        cells.addAll(withHole);
        cells.addAll(List.of(new MetricCell(500, 500), new MetricCell(501, 501))); // solo en diagonal: no contiguas

        var square = LargestSquare.of(cells).orElseThrow();
        assertThat(square.size()).isEqualTo(4);
    }

    @Test
    void matchesABruteForceSearchOnRandomGrids() {
        var random = new Random(7);
        for (int run = 0; run < 200; run++) {
            var cells = new ArrayList<MetricCell>();
            var grid = new boolean[12][12];
            for (int c = 0; c < 12; c++) {
                for (int r = 0; r < 12; r++) {
                    if (random.nextDouble() < 0.7) {
                        grid[c][r] = true;
                        cells.add(new MetricCell(c, r));
                    }
                }
            }
            var expected = 0;
            for (int c = 0; c < 12; c++) {
                for (int r = 0; r < 12; r++) {
                    for (int size = 1; c + size <= 12 && r + size <= 12; size++) {
                        var full = true;
                        for (int i = c; i < c + size && full; i++) {
                            for (int j = r; j < r + size && full; j++) {
                                full = grid[i][j];
                            }
                        }
                        if (full) expected = Math.max(expected, size);
                    }
                }
            }
            var found = LargestSquare.of(cells).map(LargestSquare.Square::size).orElse(0);
            assertThat(found).isEqualTo(expected);
            LargestSquare.of(cells).ifPresent(square -> {
                for (int i = square.col(); i < square.col() + square.size(); i++) {
                    for (int j = square.row(); j < square.row() + square.size(); j++) {
                        assertThat(cells).contains(new MetricCell(i, j));
                    }
                }
            });
        }
    }

    @Test
    void encodesAndDecodesCells() {
        var cell = new MetricCell(4_123, 2_345);
        assertThat(MetricCell.decode(cell.encode())).isEqualTo(cell);
    }
}
