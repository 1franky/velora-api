package mx.velora.api.importing;

import java.util.List;
import java.util.Objects;

/**
 * Actividad saneada y lista para deduplicar y persistir.
 *
 * @param activity actividad con las correcciones de {@code warnings} aplicadas
 * @param warnings correcciones aplicadas; vacía si el archivo estaba limpio
 * @param footprint resumen para deduplicación heurística
 * @param validationVersion versión de las reglas de validación aplicadas
 */
public record ValidatedActivity(
        ImportedActivity activity,
        List<ValidationWarning> warnings,
        ActivityFootprint footprint,
        String validationVersion) {

    public ValidatedActivity {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(footprint, "footprint");
        Objects.requireNonNull(validationVersion, "validationVersion");
        warnings = List.copyOf(warnings);
    }
}
