package mx.velora.api.geospatial.exploration;

/**
 * Celda de la cuadrícula métrica de 1 km en EPSG:6372 (ADR-018): columna y fila desde el origen de la proyección.
 * Se guarda como un solo {@code bigint}: {@code col * 2^20 + fila} (México cabe holgado: ~5 000 × 3 000 celdas).
 */
record MetricCell(int col, int row) {

    static final int SIZE_METERS = 1_000;
    private static final int SHIFT = 20;
    private static final long MASK = (1L << SHIFT) - 1;

    long encode() {
        return ((long) col << SHIFT) | row;
    }

    static MetricCell decode(long value) {
        return new MetricCell((int) (value >> SHIFT), (int) (value & MASK));
    }
}
