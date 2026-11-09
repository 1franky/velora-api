package mx.velora.api.activity;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Subtipo de actividad ciclista ({@code 02-domain-and-data-model.md}). */
public enum ActivitySubType {
    MOUNTAIN_BIKE,
    ROAD,
    GRAVEL,
    INDOOR,
    OTHER;

    /**
     * Regla de ADR-012: sin posiciones → {@code INDOOR}; si no, el subdeporte FIT; si no, el tipo de la bicicleta
     * elegida al importar; si no, {@code OTHER}.
     *
     * @param hasPositions si la actividad tiene al menos una posición válida
     * @param reportedSubSport {@code session.sub_sport} de FIT (nombre del SDK), si existe
     * @param bikeType tipo de la bicicleta ({@code MTB}, {@code ROAD}, {@code GRAVEL}, {@code OTHER}), si se eligió una
     */
    public static ActivitySubType infer(
            boolean hasPositions, @Nullable String reportedSubSport, @Nullable String bikeType) {
        if (!hasPositions) {
            return INDOOR;
        }
        var fromDevice = fromFitSubSport(reportedSubSport);
        if (fromDevice != null) {
            return fromDevice;
        }
        if (bikeType != null) {
            return switch (bikeType) {
                case "MTB" -> MOUNTAIN_BIKE;
                case "ROAD" -> ROAD;
                case "GRAVEL" -> GRAVEL;
                default -> OTHER;
            };
        }
        return OTHER;
    }

    private static @Nullable ActivitySubType fromFitSubSport(@Nullable String subSport) {
        if (subSport == null) {
            return null;
        }
        return switch (subSport.toUpperCase(Locale.ROOT)) {
            case "ROAD", "TRACK_CYCLING", "TIME_TRIAL" -> ROAD;
            case "MOUNTAIN", "DOWNHILL", "E_BIKE_MOUNTAIN" -> MOUNTAIN_BIKE;
            case "GRAVEL_CYCLING", "CYCLOCROSS" -> GRAVEL;
            case "INDOOR_CYCLING", "SPIN", "VIRTUAL_ACTIVITY" -> INDOOR;
            default -> null;
        };
    }
}
