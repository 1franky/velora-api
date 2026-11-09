package mx.velora.api.importing.parsing;

/**
 * Límites aplicados durante el parseo.
 *
 * @param maxPoints número máximo de muestras por archivo
 */
public record ParserLimits(int maxPoints) {

    /** 200 000 muestras: unas 55 h a 1 Hz (ADR-009). */
    public static final ParserLimits DEFAULT = new ParserLimits(200_000);

    public ParserLimits {
        if (maxPoints <= 0) {
            throw new IllegalArgumentException("maxPoints debe ser positivo");
        }
    }
}
