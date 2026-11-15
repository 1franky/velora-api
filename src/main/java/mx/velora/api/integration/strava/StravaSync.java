package mx.velora.api.integration.strava;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import mx.velora.api.activity.ActivityLookup;
import mx.velora.api.importing.StravaActivityDocument;
import mx.velora.api.importing.StravaImports;
import mx.velora.api.platform.logging.RedactedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Trae las rodadas de Strava al pipeline de importación (ADR-030).
 *
 * <ul>
 *   <li><b>Incremental:</b> lo posterior a {@code synced_after} (al conectar, la fecha de conexión).
 *   <li><b>Carga inicial:</b> hacia atrás desde la conexión hasta {@code velora.strava.backfill-since} o el inicio
 *       del historial, una página por vez.
 * </ul>
 *
 * Cada actividad revisada queda en {@code strava_sync_items}, así no se vuelve a pedir ni se reimporta lo que el
 * usuario borró en Velora. Si ya hay una rodada equivalente (subida como archivo), la de Strava se descarta: el
 * archivo del dispositivo trae más datos. Al agotarse la cuota de Strava o el límite por ejecución, el avance queda
 * guardado y la siguiente ejecución sigue desde ahí.
 */
@Service
public class StravaSync {

    /** {@code sport_type} de Strava que son ciclismo. */
    static final Set<String> CYCLING =
            Set.of("RIDE", "MOUNTAINBIKERIDE", "GRAVELRIDE", "EBIKERIDE", "EMOUNTAINBIKERIDE", "VIRTUALRIDE");

    static final int PAGE_SIZE = 50;

    /** Páginas de actividades nuevas por ejecución; más que eso solo pasa tras semanas sin sincronizar. */
    static final int MAX_INCREMENTAL_PAGES = 4;

    private static final Logger log = LoggerFactory.getLogger(StravaSync.class);

    /** Resultado de una ejecución para un usuario. */
    public record Result(
            int submitted,
            int skipped,
            boolean backfillDone,
            boolean rateLimited,
            @Nullable String error) {}

    private final StravaProperties properties;
    private final StravaConnectionRepository repository;
    private final StravaConnections connections;
    private final StravaApi api;
    private final ActivityLookup activities;
    private final StravaImports imports;
    private final Clock clock;

    StravaSync(
            StravaProperties properties,
            StravaConnectionRepository repository,
            StravaConnections connections,
            StravaApi api,
            ActivityLookup activities,
            StravaImports imports,
            Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.connections = connections;
        this.api = api;
        this.activities = activities;
        this.imports = imports;
        this.clock = clock;
    }

    /** Sincroniza a cada usuario conectado; el fallo de uno no detiene a los demás. */
    public void syncAll() {
        for (var userId : repository.connectedUsers()) {
            try {
                syncUser(userId);
            } catch (RuntimeException e) {
                log.error("Falló la sincronización con Strava", RedactedException.of(e));
            }
        }
    }

    public Result syncUser(UUID userId) {
        var state = repository.syncState(userId).orElse(null);
        if (!properties.enabled() || state == null) {
            return new Result(0, 0, false, false, null);
        }
        var run = new Run(userId, state);
        String error = null;
        var rateLimited = false;
        try {
            run.token = connections.accessToken(userId);
            run.incremental();
            run.backfill();
        } catch (StravaApi.RateLimitedException e) {
            rateLimited = true;
        } catch (StravaAuth.RejectedException e) {
            error = "AUTH_REVOKED";
            log.warn("Strava rechazó el acceso; hay que volver a conectar la cuenta");
        } catch (StravaAuth.UnavailableException e) {
            error = "STRAVA_UNAVAILABLE";
            log.warn("Strava no respondió durante la sincronización", RedactedException.of(e));
        } finally {
            repository.saveProgress(userId, run.after, run.before, run.backfillDone);
            repository.finishRun(userId, clock.instant(), error);
        }
        log.info(
                "Sincronización con Strava: {} enviadas a importar, {} omitidas, carga inicial {}{}",
                run.submitted,
                run.skipped,
                run.backfillDone ? "completa" : "en curso",
                rateLimited ? "; cuota de Strava agotada, continúa en la siguiente ventana" : "");
        return new Result(run.submitted, run.skipped, run.backfillDone, rateLimited, error);
    }

    /** Estado de una ejecución; el avance se guarda después de cada actividad. */
    private final class Run {
        final UUID userId;

        @Nullable String token;

        Instant after;
        Instant before;
        boolean backfillDone;
        int budget = properties.sync().perRun();
        int submitted;
        int skipped;

        Run(UUID userId, StravaConnectionRepository.SyncState state) {
            this.userId = userId;
            this.after = state.syncedAfter() != null ? state.syncedAfter() : state.connectedAt();
            this.before = state.backfillBefore() != null ? state.backfillBefore() : state.connectedAt();
            this.backfillDone = state.backfillDone();
        }

        void incremental() {
            var fresh = new ArrayList<StravaApi.Activity>();
            for (int page = 1; page <= MAX_INCREMENTAL_PAGES; page++) {
                var batch = api.activities(token(), null, after, page, PAGE_SIZE);
                fresh.addAll(batch);
                if (batch.size() < PAGE_SIZE) {
                    break;
                }
            }
            fresh.sort(Comparator.comparing(StravaApi.Activity::startDate));
            for (var activity : fresh) {
                if (budget <= 0) {
                    return;
                }
                handle(activity);
                if (activity.startDate().isAfter(after)) {
                    after = activity.startDate();
                }
                save();
            }
        }

        void backfill() {
            var since = properties.backfillSince() == null
                    ? null
                    : properties.backfillSince().atStartOfDay(ZoneOffset.UTC).toInstant();
            while (!backfillDone && budget > 0) {
                var batch = api.activities(token(), before, null, 1, PAGE_SIZE);
                if (batch.isEmpty()) {
                    backfillDone = true;
                    break;
                }
                for (var activity : batch) {
                    if (since != null && activity.startDate().isBefore(since)) {
                        backfillDone = true;
                        break;
                    }
                    if (budget <= 0) {
                        break;
                    }
                    handle(activity);
                    if (activity.startDate().isBefore(before)) {
                        before = activity.startDate();
                    }
                    save();
                }
            }
            save();
        }

        void handle(StravaApi.Activity activity) {
            if (repository.alreadySeen(userId, activity.id())) {
                return;
            }
            var summary = activity.summary();
            var sport = summary.sportType() == null ? "" : summary.sportType().toUpperCase(Locale.ROOT);
            if (!CYCLING.contains(sport)) {
                skip(activity, "NOT_CYCLING");
                return;
            }
            if (activity.manual()) {
                skip(activity, "NO_DATA");
                return;
            }
            if (activities.hasSimilar(userId, summary.startDate(), summary.distance())) {
                skip(activity, "DUPLICATE");
                return;
            }
            StravaActivityDocument.Streams streams;
            try {
                streams = api.streams(token(), activity.id());
            } catch (StravaApi.NotFoundException e) {
                skip(activity, "NO_DATA");
                return;
            }
            budget--;
            if (streams.time() == null || streams.time().isEmpty()) {
                skip(activity, "NO_DATA");
                return;
            }
            var jobId =
                    imports.submit(userId, new StravaActivityDocument(StravaActivityDocument.FORMAT, summary, streams));
            repository.recordItem(userId, activity.id(), "SUBMITTED", jobId, clock.instant());
            submitted++;
        }

        void skip(StravaApi.Activity activity, String outcome) {
            repository.recordItem(userId, activity.id(), outcome, null, clock.instant());
            skipped++;
        }

        void save() {
            repository.saveProgress(userId, after, before, backfillDone);
        }

        String token() {
            if (token == null) {
                throw new IllegalStateException("sin token");
            }
            return token;
        }
    }
}
