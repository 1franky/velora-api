package mx.velora.api.importing;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * "Original" de una actividad de Strava (ADR-030): el resumen y los streams tal como los entrega su API, guardado en
 * el almacén de originales para importar y reprocesar igual que un archivo. Lo arma la sincronización
 * ({@code integration.strava}) y lo lee {@code StravaParser}. Cambiar sus campos exige subir {@link #FORMAT}.
 *
 * @param format versión del documento
 */
public record StravaActivityDocument(String format, Summary activity, Streams streams) {

    public static final String FORMAT = "velora-strava/1";

    public StravaActivityDocument {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(streams, "streams");
    }

    /** Campos del {@code SummaryActivity} de Strava que se usan. Unidades SI, como los entrega Strava. */
    public record Summary(
            long id,
            @Nullable String name,
            @Nullable String sportType,
            Instant startDate,
            boolean trainer,
            @Nullable Double elapsedTime,
            @Nullable Double movingTime,
            @Nullable Double distance,
            @Nullable Double totalElevationGain,
            @Nullable Double averageSpeed,
            @Nullable Double maxSpeed,
            @Nullable Double averageHeartrate,
            @Nullable Double maxHeartrate,
            @Nullable Double averageCadence,
            @Nullable Double averageWatts,
            @Nullable Double maxWatts) {}

    /**
     * Streams alineados por índice con {@code time} (segundos desde {@code startDate}). Un stream ausente es
     * {@code null}; un valor faltante dentro de un stream también.
     */
    public record Streams(
            List<Integer> time,
            @Nullable List<double @Nullable []> latlng,
            @Nullable List<@Nullable Double> altitude,
            @Nullable List<@Nullable Double> distance,
            @Nullable List<@Nullable Double> heartrate,
            @Nullable List<@Nullable Double> cadence,
            @Nullable List<@Nullable Double> watts,
            @Nullable List<@Nullable Double> velocitySmooth,
            @Nullable List<@Nullable Double> temp) {}
}
