package mx.velora.api.integration.strava;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;
import mx.velora.api.importing.StravaActivityDocument;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link StravaApi} contra {@code /api/v3}. Lee las cabeceras de uso de cada respuesta y, cuando la cuota de lectura
 * está por agotarse, deja de pedir hasta que empiece la siguiente ventana: {@link RateLimitedException} sin salir a la
 * red (ADR-030: se respetan los límites). Las ventanas de Strava empiezan cada cuarto de hora y a medianoche UTC.
 */
class HttpStravaApi implements StravaApi {

    /** Solicitudes que se dejan libres en cada ventana (15 min y día). */
    static final int MARGIN_15_MIN = 5;

    static final int MARGIN_DAY = 20;

    private static final String STREAM_KEYS =
            "time,latlng,altitude,distance,heartrate,cadence,watts,velocity_smooth,temp";

    /** Campos del {@code SummaryActivity} de Strava, con sus nombres originales. */
    record SummaryResponse(
            long id,
            @Nullable String name,
            @Nullable String sport_type,
            Instant start_date,
            boolean trainer,
            boolean manual,
            @Nullable Double elapsed_time,
            @Nullable Double moving_time,
            @Nullable Double distance,
            @Nullable Double total_elevation_gain,
            @Nullable Double average_speed,
            @Nullable Double max_speed,
            @Nullable Double average_heartrate,
            @Nullable Double max_heartrate,
            @Nullable Double average_cadence,
            @Nullable Double average_watts,
            @Nullable Double max_watts) {}

    record IntStream(List<Integer> data) {}

    record DoubleStream(List<@Nullable Double> data) {}

    record LatLngStream(List<double @Nullable []> data) {}

    /** Respuesta de {@code /activities/{id}/streams?key_by_type=true}. */
    record StreamsResponse(
            @Nullable IntStream time,
            @Nullable LatLngStream latlng,
            @Nullable DoubleStream altitude,
            @Nullable DoubleStream distance,
            @Nullable DoubleStream heartrate,
            @Nullable DoubleStream cadence,
            @Nullable DoubleStream watts,
            @Nullable DoubleStream velocity_smooth,
            @Nullable DoubleStream temp) {}

    private final RestClient http;
    private final Clock clock;
    private volatile Instant blockedUntil = Instant.EPOCH;

    HttpStravaApi(RestClient.Builder builder, StravaProperties properties, Clock clock) {
        this.http = builder.baseUrl(properties.baseUrl() + "/api/v3").build();
        this.clock = clock;
    }

    @Override
    public List<Activity> activities(
            String accessToken, @Nullable Instant before, @Nullable Instant after, int page, int perPage) {
        var response = call(() -> http.get()
                .uri(uri -> {
                    uri.path("/athlete/activities").queryParam("page", page).queryParam("per_page", perPage);
                    if (before != null) {
                        uri.queryParam("before", before.getEpochSecond());
                    }
                    if (after != null) {
                        uri.queryParam("after", after.getEpochSecond());
                    }
                    return uri.build();
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .toEntity(SummaryResponse[].class));
        var body = response.getBody();
        return body == null
                ? List.of()
                : java.util.Arrays.stream(body).map(HttpStravaApi::toActivity).toList();
    }

    @Override
    public StravaActivityDocument.Streams streams(String accessToken, long activityId) {
        var response = call(() -> http.get()
                .uri(uri -> uri.path("/activities/{id}/streams")
                        .queryParam("keys", STREAM_KEYS)
                        .queryParam("key_by_type", true)
                        .build(activityId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .toEntity(StreamsResponse.class));
        var body = response.getBody();
        if (body == null || body.time() == null) {
            return new StravaActivityDocument.Streams(List.of(), null, null, null, null, null, null, null, null);
        }
        return new StravaActivityDocument.Streams(
                body.time().data(),
                body.latlng() == null ? null : body.latlng().data(),
                data(body.altitude()),
                data(body.distance()),
                data(body.heartrate()),
                data(body.cadence()),
                data(body.watts()),
                data(body.velocity_smooth()),
                data(body.temp()));
    }

    private static @Nullable List<@Nullable Double> data(@Nullable DoubleStream stream) {
        return stream == null ? null : stream.data();
    }

    private static Activity toActivity(SummaryResponse r) {
        return new Activity(
                new StravaActivityDocument.Summary(
                        r.id(),
                        r.name(),
                        r.sport_type(),
                        r.start_date(),
                        r.trainer(),
                        r.elapsed_time(),
                        r.moving_time(),
                        r.distance(),
                        r.total_elevation_gain(),
                        r.average_speed(),
                        r.max_speed(),
                        r.average_heartrate(),
                        r.max_heartrate(),
                        r.average_cadence(),
                        r.average_watts(),
                        r.max_watts()),
                r.manual());
    }

    private <T> ResponseEntity<T> call(Supplier<ResponseEntity<T>> request) {
        var now = clock.instant();
        if (now.isBefore(blockedUntil)) {
            throw new RateLimitedException();
        }
        ResponseEntity<T> response;
        try {
            response = request.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                blockedUntil = nextQuarterHour(now);
            }
            throw translate(e.getStatusCode());
        } catch (RestClientException e) {
            throw new StravaAuth.UnavailableException(e);
        }
        var near = nearLimit(response.getHeaders());
        if (near == Window.DAY) {
            blockedUntil = now.atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } else if (near == Window.QUARTER_HOUR) {
            blockedUntil = nextQuarterHour(now);
        }
        return response;
    }

    static Instant nextQuarterHour(Instant now) {
        var truncated = now.truncatedTo(ChronoUnit.HOURS);
        var minutes = now.atZone(ZoneOffset.UTC).getMinute();
        return truncated.plus(Duration.ofMinutes((minutes / 15 + 1) * 15L));
    }

    private static RuntimeException translate(HttpStatusCode status) {
        return switch (status.value()) {
            case 401, 403 -> new StravaAuth.RejectedException();
            case 404 -> new NotFoundException();
            case 429 -> new RateLimitedException();
            default -> new StravaAuth.UnavailableException(new IllegalStateException("HTTP " + status.value()));
        };
    }

    enum Window {
        NONE,
        QUARTER_HOUR,
        DAY
    }

    /**
     * Uso de lectura según {@code X-ReadRateLimit-*} (o {@code X-RateLimit-*} si no vienen): "15min,día". Indica qué
     * ventana está por agotarse; la diaria tiene prioridad.
     */
    static Window nearLimit(HttpHeaders headers) {
        var limit = pair(headers, "X-ReadRateLimit-Limit", "X-RateLimit-Limit");
        var usage = pair(headers, "X-ReadRateLimit-Usage", "X-RateLimit-Usage");
        if (limit == null || usage == null) {
            return Window.NONE;
        }
        if (usage[1] >= limit[1] - MARGIN_DAY) {
            return Window.DAY;
        }
        return usage[0] >= limit[0] - MARGIN_15_MIN ? Window.QUARTER_HOUR : Window.NONE;
    }

    private static int @Nullable [] pair(HttpHeaders headers, String name, String fallback) {
        var value = headers.getFirst(name);
        if (value == null) {
            value = headers.getFirst(fallback);
        }
        if (value == null) {
            return null;
        }
        var parts = value.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
