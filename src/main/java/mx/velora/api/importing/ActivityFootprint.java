package mx.velora.api.importing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Resumen mínimo de una actividad para deduplicación heurística y fecha deportiva local.
 *
 * @param startedAt instante UTC de la primera muestra con tiempo
 * @param elapsedSeconds segundos entre la primera y la última muestra con tiempo, pausas incluidas
 * @param distance distancia total; {@code null} si no hay datos para informarla ni calcularla
 * @param boundingBox recuadro de las posiciones válidas; {@code null} sin posiciones (p. ej. indoor)
 * @param algorithmVersion versión del cálculo de los valores derivados
 */
public record ActivityFootprint(
        Instant startedAt,
        double elapsedSeconds,
        @Nullable Measurement distance,
        @Nullable BoundingBox boundingBox,
        String algorithmVersion) {

    public ActivityFootprint {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        if (elapsedSeconds < 0) {
            throw new IllegalArgumentException("elapsedSeconds no puede ser negativo");
        }
    }

    /** Fecha deportiva local: la del inicio en la zona horaria del usuario al importar (`02`, reglas de integridad). */
    public LocalDate localStartDate(ZoneId timeZone) {
        return startedAt.atZone(timeZone).toLocalDate();
    }
}
