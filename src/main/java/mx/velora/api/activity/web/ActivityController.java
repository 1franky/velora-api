package mx.velora.api.activity.web;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mx.velora.api.activity.ActivityDeletion;
import mx.velora.api.activity.ActivitySubType;
import mx.velora.api.activity.ActivitySummary;
import mx.velora.api.activity.StreamType;
import mx.velora.api.activity.persistence.ActivityQueries;
import mx.velora.api.activity.persistence.ActivityQueries.Filter;
import mx.velora.api.activity.persistence.ActivityQueries.Position;
import mx.velora.api.identity.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/activities} según {@code openapi/openapi-v1.yaml}. */
@RestController
@RequestMapping("/api/v1/activities")
class ActivityController {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 100;

    record Page(List<ActivitySummary> items, @Nullable String nextCursor) {}

    record OriginalFile(String format, long sizeBytes, Instant importedAt) {}

    record Detail(
            @JsonUnwrapped ActivitySummary summary,
            String timeZone,
            String sourceType,
            @Nullable UUID deviceId,
            @Nullable BigDecimal elevationLossMeters,
            @Nullable BigDecimal maxSpeedMps,
            @Nullable Integer averageHeartRateBpm,
            @Nullable Integer maxHeartRateBpm,
            @Nullable Integer averagePowerWatts,
            @Nullable Integer maxPowerWatts,
            @Nullable Integer averageCadenceRpm,
            @JsonRawValue String metricsProvenance,
            boolean hasTrack,
            List<StreamType> streamTypes,
            int lapCount,
            OriginalFile originalFile) {}

    record StreamsResponse(int sampleCount, Map<StreamType, @Nullable Double[]> streams) {}

    record Laps(List<ActivityQueries.Lap> items) {}

    record TrackProperties(UUID activityId, int pointCount, int returnedPointCount, int zoom) {}

    record TrackFeature(
            String type, double[] bbox, @JsonRawValue String geometry, TrackProperties properties) {}

    static final int MAX_ZOOM = 22;
    static final double METERS_PER_PIXEL_ZOOM_0 = 156_543.03392;
    static final double METERS_PER_DEGREE = 111_320;

    private final ActivityQueries queries;
    private final ActivityDeletion deletion;

    ActivityController(ActivityQueries queries, ActivityDeletion deletion) {
        this.queries = queries;
        this.deletion = deletion;
    }

    @GetMapping
    Page list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) @Nullable LocalDate from,
            @RequestParam(required = false) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable UUID bikeId,
            @RequestParam(required = false) @Nullable ActivitySubType subType,
            @RequestParam(required = false) @Nullable Double minDistanceM,
            @RequestParam(required = false) @Nullable Double maxDistanceM,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new InvalidActivityQueryException();
        }
        if ((from != null && to != null && from.isAfter(to))
                || (minDistanceM != null && minDistanceM < 0)
                || (maxDistanceM != null && maxDistanceM < 0)
                || (minDistanceM != null && maxDistanceM != null && minDistanceM > maxDistanceM)) {
            throw new InvalidActivityQueryException();
        }
        var filter = new Filter(from, to, bikeId, subType, minDistanceM, maxDistanceM);
        // Se pide uno extra para saber si hay otra página sin una segunda consulta.
        var rows = queries.list(user.userId(), filter, decodeCursor(cursor), size + 1);
        if (rows.size() <= size) {
            return new Page(rows, null);
        }
        var page = rows.subList(0, size);
        var last = page.getLast();
        return new Page(page, encodeCursor(new Position(last.startedAt(), last.id())));
    }

    @GetMapping("/{activityId}")
    Detail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID activityId) {
        var d = queries.detail(user.userId(), activityId).orElseThrow(ActivityNotFoundException::new);
        return new Detail(
                d.summary(),
                d.timeZone(),
                d.sourceType(),
                d.deviceId(),
                d.elevationLossMeters(),
                d.maxSpeedMps(),
                d.averageHeartRateBpm(),
                d.maxHeartRateBpm(),
                d.averagePowerWatts(),
                d.maxPowerWatts(),
                d.averageCadenceRpm(),
                d.metricsProvenanceJson(),
                d.hasTrack(),
                d.streamTypes(),
                d.lapCount(),
                new OriginalFile(d.fileFormat(), d.fileSizeBytes(), d.importedAt()));
    }

    @GetMapping("/{activityId}/streams")
    StreamsResponse streams(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID activityId,
            @RequestParam(required = false) @Nullable String types) {
        requireOwned(user, activityId);
        var streams = queries.streams(activityId, parseTypes(types));
        return new StreamsResponse(streams.sampleCount(), streams.values());
    }

    /**
     * Recorrido simplificado con una tolerancia de ~1 píxel en el zoom pedido: metros por píxel en Web Mercator
     * (156 543 m/px en el zoom 0, ecuador) convertidos a grados.
     */
    @GetMapping(value = "/{activityId}/track", produces = "application/geo+json")
    TrackFeature track(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID activityId,
            @RequestParam(defaultValue = "14") int zoom) {
        if (zoom < 0 || zoom > MAX_ZOOM) {
            throw new InvalidActivityQueryException();
        }
        requireOwned(user, activityId);
        var toleranceDegrees = METERS_PER_PIXEL_ZOOM_0 / Math.pow(2, zoom) / METERS_PER_DEGREE;
        var track = queries.track(activityId, toleranceDegrees).orElseThrow(TrackNotFoundException::new);
        return new TrackFeature(
                "Feature",
                track.bbox(),
                track.geometryJson(),
                new TrackProperties(activityId, track.pointCount(), track.returnedPointCount(), zoom));
    }

    @GetMapping("/{activityId}/laps")
    Laps laps(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID activityId) {
        requireOwned(user, activityId);
        return new Laps(queries.laps(activityId));
    }

    @DeleteMapping("/{activityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID activityId) {
        if (!deletion.delete(user.userId(), activityId)) {
            throw new ActivityNotFoundException();
        }
    }

    private void requireOwned(AuthenticatedUser user, UUID activityId) {
        if (!queries.exists(user.userId(), activityId)) {
            throw new ActivityNotFoundException();
        }
    }

    private static @Nullable Set<StreamType> parseTypes(@Nullable String types) {
        if (types == null || types.isBlank()) {
            return null;
        }
        var set = EnumSet.noneOf(StreamType.class);
        for (var name : types.split(",")) {
            try {
                set.add(StreamType.valueOf(name.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new InvalidStreamTypesException();
            }
        }
        return set;
    }

    /** Cursor opaco: base64url de {@code <microsegundos de started_at>|<id>}. */
    static String encodeCursor(Position position) {
        var micros = position.startedAt().getEpochSecond() * 1_000_000
                + position.startedAt().getNano() / 1_000;
        var raw = micros + "|" + position.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    static @Nullable Position decodeCursor(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            var raw = StandardCharsets.US_ASCII
                    .newDecoder()
                    .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor)))
                    .toString();
            var separator = raw.indexOf('|');
            var micros = Long.parseLong(raw.substring(0, separator));
            var id = UUID.fromString(raw.substring(separator + 1));
            return new Position(
                    Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1_000L),
                    id);
        } catch (RuntimeException | java.nio.charset.CharacterCodingException e) {
            throw new InvalidCursorException();
        }
    }

    static final class ActivityNotFoundException extends ErrorResponseException {
        ActivityNotFoundException() {
            super(HttpStatus.NOT_FOUND);
        }
    }

    static final class InvalidCursorException extends ErrorResponseException {
        InvalidCursorException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }

    static final class InvalidActivityQueryException extends ErrorResponseException {
        InvalidActivityQueryException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }

    static final class TrackNotFoundException extends ErrorResponseException {
        TrackNotFoundException() {
            super(HttpStatus.NOT_FOUND);
        }
    }

    static final class InvalidStreamTypesException extends ErrorResponseException {
        InvalidStreamTypesException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }
}
