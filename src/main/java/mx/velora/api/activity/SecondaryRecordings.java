package mx.velora.api.activity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mx.velora.api.activity.persistence.ActivityWriter;
import mx.velora.api.activity.persistence.SecondaryRecordingStore;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de las grabaciones secundarias (ADR-032). Las registra {@code importing} cuando termina de procesar
 * el archivo; las asigna a una actividad por solape de tiempos ({@link #MATCH_VERSION}) o por decisión del usuario.
 * Nunca crean una actividad ni entran en la analítica.
 */
@Service
public class SecondaryRecordings {

    /** Versión del algoritmo de asignación automática. */
    public static final String MATCH_VERSION = "secondary-match/1";

    /** Solape mínimo, como fracción de la más corta de las dos ventanas (ADR-032, aceptada con 50 %). */
    static final double MIN_OVERLAP = 0.5;

    private final SecondaryRecordingStore store;
    private final ActivityWriter activities;
    private final Clock clock;

    SecondaryRecordings(SecondaryRecordingStore store, ActivityWriter activities, Clock clock) {
        this.store = store;
        this.activities = activities;
        this.clock = clock;
    }

    /**
     * Guarda una grabación procesada y la asigna si hay exactamente una actividad candidata. Idempotente: si ya existe
     * la del mismo {@code clientRecordingId} con el mismo original, devuelve su id.
     *
     * @param processed actividad que produjo el pipeline de importación con el archivo; solo se usan sus series,
     *     métricas y original, nunca se registra como actividad
     * @throws DuplicateRecordingException si otra grabación del usuario ya tiene ese mismo archivo
     */
    @Transactional
    public UUID register(UUID clientRecordingId, String source, String completeness, NewActivity processed) {
        var userId = processed.userId();
        var existing = store.byClientId(userId, source, clientRecordingId);
        if (existing.isPresent()) {
            if (existing.get().original().storageKey().equals(processed.file().storageKey())) {
                return existing.get().id();
            }
            throw new DuplicateRecordingException(existing.get().id());
        }
        var sameFile = store.bySha256(userId, processed.file().sha256());
        if (sameFile.isPresent()) {
            throw new DuplicateRecordingException(sameFile.get());
        }
        var effective = ActivityWriter.effective(processed.metrics()).values();
        var elapsed = effective.getOrDefault(Metric.ELAPSED_TIME, 0.0);
        var startedAt = processed.startedAt();
        var endedAt = startedAt.plus(Duration.ofMillis(Math.round(elapsed * 1000)));
        var id = UUID.randomUUID();
        var now = clock.instant();
        store.insert(new SecondaryRecordingStore.NewRow(
                id,
                userId,
                source,
                clientRecordingId,
                completeness,
                processed.sourceType(),
                processed.file(),
                startedAt,
                endedAt,
                elapsed,
                effective.get(Metric.DISTANCE),
                processed.samples().count(),
                integer(effective.get(Metric.AVERAGE_HEART_RATE)),
                integer(effective.get(Metric.MAX_HEART_RATE)),
                now));
        store.insertStreams(id, processed.samples());
        store.insertTrack(id, processed.samples());
        var candidates = store.overlappingActivities(userId, startedAt, endedAt, MIN_OVERLAP);
        if (candidates.size() == 1) {
            store.assign(userId, id, candidates.getFirst(), "AUTO", now);
        }
        return id;
    }

    public Optional<SecondaryRecording> find(UUID userId, UUID id) {
        return store.find(userId, id);
    }

    public Optional<SecondaryRecording> byClientId(UUID userId, String source, UUID clientRecordingId) {
        return store.byClientId(userId, source, clientRecordingId);
    }

    /** @param assigned {@code null}: todas; {@code true}: adjuntas; {@code false}: sin asignar */
    public List<SecondaryRecording> list(UUID userId, @Nullable Boolean assigned, int offset, int limit) {
        return store.list(userId, assigned, offset, limit);
    }

    public long count(UUID userId, @Nullable Boolean assigned) {
        return store.count(userId, assigned);
    }

    public List<SecondaryRecording> forActivity(UUID userId, UUID activityId) {
        return store.forActivity(userId, activityId);
    }

    /** Si el usuario ya importó un archivo idéntico como actividad (mismo SHA-256). */
    public Optional<UUID> activityWithOriginal(UUID userId, byte[] sha256) {
        return activities.findActivityWithOriginal(userId, sha256);
    }

    /**
     * Adjunta a mano a {@code activityId}, o desasigna con {@code null}. La asignación automática no vuelve a mover
     * una grabación decidida por el usuario.
     *
     * @return la grabación actualizada; vacío si no existe
     * @throws ActivityNotFoundException si la actividad no existe o es de otro usuario
     */
    @Transactional
    public Optional<SecondaryRecording> assign(UUID userId, UUID id, @Nullable UUID activityId) {
        if (activityId != null
                && activities.findOwner(activityId).filter(userId::equals).isEmpty()) {
            throw new ActivityNotFoundException();
        }
        if (!store.assign(userId, id, activityId, "USER", clock.instant())) {
            return Optional.empty();
        }
        return store.find(userId, id);
    }

    /**
     * Borra la fila (series y recorrido en cascada). El original lo borra quien llama, después de confirmar.
     *
     * @return el original que quedó sin referencia; vacío si no existía
     */
    @Transactional
    public Optional<SecondaryRecording.Original> delete(UUID userId, UUID id) {
        var recording = store.find(userId, id);
        if (recording.isEmpty() || !store.delete(userId, id)) {
            return Optional.empty();
        }
        return Optional.of(recording.get().original());
    }

    /** Todas las del usuario, para la exportación (ADR-028). */
    public List<SecondaryRecording> all(UUID userId) {
        return store.all(userId);
    }

    /**
     * Tras registrar una actividad: adjunta las grabaciones sin asignar para las que esa actividad es la única
     * candidata. Así funciona aunque la grabación llegue antes que la actividad (el reloj sube antes que Strava).
     */
    void attachPendingTo(UUID activityId, UUID userId, Instant activityStart, Instant activityEnd) {
        var now = clock.instant();
        for (var recording : store.unassignedAutoCandidates(userId, activityStart, activityEnd)) {
            var candidates =
                    store.overlappingActivities(userId, recording.startedAt(), recording.endedAt(), MIN_OVERLAP);
            if (candidates.size() == 1 && candidates.getFirst().equals(activityId)) {
                store.assign(userId, recording.id(), activityId, "AUTO", now);
            }
        }
    }

    /** Quita la fila de una grabación que se recuperó como actividad; su original pasa a la actividad. */
    void consume(UUID userId, UUID id) {
        store.delete(userId, id);
    }

    private static @Nullable Integer integer(@Nullable Double value) {
        return value == null ? null : (int) Math.round(value);
    }

    /** Otra grabación del usuario ya tiene ese archivo o ese id de dispositivo con otro archivo. */
    public static final class DuplicateRecordingException extends RuntimeException {
        private final UUID existingRecordingId;

        public DuplicateRecordingException(UUID existingRecordingId) {
            super("Ya existe la grabación secundaria " + existingRecordingId);
            this.existingRecordingId = existingRecordingId;
        }

        public UUID existingRecordingId() {
            return existingRecordingId;
        }
    }

    /** La actividad indicada no existe o no es del usuario. */
    public static final class ActivityNotFoundException extends RuntimeException {
        public ActivityNotFoundException() {
            super("Actividad no encontrada");
        }
    }
}
