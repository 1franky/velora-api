package mx.velora.api.integration.strava;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import mx.velora.api.importing.StravaActivityDocument;
import org.jspecify.annotations.Nullable;

/**
 * Doble de {@link StravaApi}: un historial en memoria con streams sintéticos (una recta de 10 min a 7 m/s). Simula
 * que {@code after} no garantiza orden y permite agotar la cuota o revocar el acceso.
 */
public class FakeStravaApi implements StravaApi {

    final List<Activity> history = new CopyOnWriteArrayList<>();
    final Map<Long, Boolean> withoutGps = new ConcurrentHashMap<>();
    final AtomicInteger streamCalls = new AtomicInteger();
    volatile int streamsBeforeRateLimit = Integer.MAX_VALUE;
    volatile boolean revoked;

    void reset() {
        history.clear();
        withoutGps.clear();
        streamCalls.set(0);
        streamsBeforeRateLimit = Integer.MAX_VALUE;
        revoked = false;
    }

    void add(long id, String sportType, Instant start, boolean trainer, boolean manual, double distance) {
        history.add(new Activity(
                new StravaActivityDocument.Summary(
                        id,
                        "Strava " + id,
                        sportType,
                        start,
                        trainer,
                        600.0,
                        590.0,
                        distance,
                        12.0,
                        7.0,
                        9.0,
                        140.0,
                        160.0,
                        85.0,
                        200.0,
                        350.0),
                manual));
        if (trainer) {
            withoutGps.put(id, true);
        }
    }

    @Override
    public List<Activity> activities(
            String accessToken, @Nullable Instant before, @Nullable Instant after, int page, int perPage) {
        if (revoked) {
            throw new StravaAuth.RejectedException();
        }
        var matching = new ArrayList<Activity>();
        for (var activity : history) {
            if ((before == null || activity.startDate().isBefore(before))
                    && (after == null || activity.startDate().isAfter(after))) {
                matching.add(activity);
            }
        }
        matching.sort(Comparator.comparing(Activity::startDate).reversed());
        if (after != null) {
            Collections.shuffle(matching);
        }
        var from = Math.min((page - 1) * perPage, matching.size());
        return List.copyOf(matching.subList(from, Math.min(from + perPage, matching.size())));
    }

    @Override
    public StravaActivityDocument.Streams streams(String accessToken, long activityId) {
        if (revoked) {
            throw new StravaAuth.RejectedException();
        }
        if (streamCalls.get() >= streamsBeforeRateLimit) {
            throw new RateLimitedException();
        }
        streamCalls.incrementAndGet();
        var gps = !withoutGps.containsKey(activityId);
        var time = new ArrayList<Integer>();
        var latlng = new ArrayList<double[]>();
        var altitude = new ArrayList<@Nullable Double>();
        var distance = new ArrayList<@Nullable Double>();
        var heartrate = new ArrayList<@Nullable Double>();
        var watts = new ArrayList<@Nullable Double>();
        var speed = new ArrayList<@Nullable Double>();
        for (int i = 0; i < 600; i++) {
            time.add(i);
            // Coordenadas sintéticas, sin relación con rodadas reales.
            latlng.add(new double[] {10.0, 20.0 + i * 0.0000640});
            altitude.add(100.0 + i * 0.01);
            distance.add(i * 7.0);
            heartrate.add(140.0);
            watts.add(200.0);
            speed.add(7.0);
        }
        return new StravaActivityDocument.Streams(
                time, gps ? latlng : null, altitude, distance, heartrate, null, watts, speed, null);
    }
}
