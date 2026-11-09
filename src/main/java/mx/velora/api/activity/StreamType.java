package mx.velora.api.activity;

/**
 * Streams de una actividad y su escala de cuantización en el códec {@code velora-streams/1} (ADR-012): resolución
 * igual o mejor que la del dispositivo.
 */
public enum StreamType {
    /** Milisegundos desde {@code started_at}. */
    TIME(1),
    /** Grados; 1e-7° ≈ 1,1 cm. */
    LATITUDE(1e7),
    LONGITUDE(1e7),
    /** Metros; 0,1 m. */
    ALTITUDE(10),
    /** Metros acumulados; 1 cm. */
    DISTANCE(100),
    /** m/s; 1 mm/s. */
    SPEED(1000),
    HEART_RATE(1),
    CADENCE(1),
    POWER(1),
    /** °C; 0,1 °C. */
    TEMPERATURE(10);

    private final double scale;

    StreamType(double scale) {
        this.scale = scale;
    }

    public double scale() {
        return scale;
    }
}
