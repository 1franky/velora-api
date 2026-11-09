package mx.velora.api.importing;

import java.util.Objects;

/**
 * Corrección aplicada durante la validación. Solo contiene el tipo y cuántas muestras afectó, nunca valores.
 *
 * @param code tipo de corrección
 * @param affectedCount número de muestras o vueltas afectadas
 */
public record ValidationWarning(Code code, int affectedCount) {

    public enum Code {
        /** Coordenadas fuera de rango o en (0,0): se eliminó la posición. */
        INVALID_COORDINATES,
        /** Saltos de GPS físicamente imposibles: se eliminó la posición del punto atípico. */
        GPS_JUMPS,
        /** Distancias, velocidades o tiempos negativos: el valor quedó en {@code null}. */
        NEGATIVE_VALUES,
        /** Marcas de tiempo que retroceden respecto a la muestra anterior: se conservan tal cual. */
        NON_MONOTONIC_TIME
    }

    public ValidationWarning {
        Objects.requireNonNull(code, "code");
        if (affectedCount <= 0) {
            throw new IllegalArgumentException("affectedCount debe ser positivo");
        }
    }
}
