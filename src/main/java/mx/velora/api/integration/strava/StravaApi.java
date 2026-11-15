package mx.velora.api.integration.strava;

import java.time.Instant;
import java.util.List;
import mx.velora.api.importing.StravaActivityDocument;
import org.jspecify.annotations.Nullable;

/** Lectura de actividades de la API v3 de Strava. Una interfaz para que las pruebas usen un doble sin red. */
public interface StravaApi {

    /** Resumen de una actividad del atleta ({@code SummaryActivity}). */
    record Activity(StravaActivityDocument.Summary summary, boolean manual) {
        public long id() {
            return summary.id();
        }

        public Instant startDate() {
            return summary.startDate();
        }
    }

    /** Se agotó la cuota de lectura de la ventana actual (429 o cerca del límite); continuar en la siguiente. */
    final class RateLimitedException extends RuntimeException {
        public RateLimitedException() {
            super("Límite de uso de la API de Strava");
        }
    }

    /** La actividad ya no existe en Strava. */
    final class NotFoundException extends RuntimeException {
        public NotFoundException() {
            super("La actividad no existe en Strava");
        }
    }

    /**
     * Actividades del atleta. Con {@code before}, de la más reciente a la más antigua; con {@code after}, Strava
     * no garantiza el orden y quien llama ordena.
     */
    List<Activity> activities(
            String accessToken, @Nullable Instant before, @Nullable Instant after, int page, int perPage);

    /** Streams de una actividad, alineados con {@code time}. */
    StravaActivityDocument.Streams streams(String accessToken, long activityId);
}
