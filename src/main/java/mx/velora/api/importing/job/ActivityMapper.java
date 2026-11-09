package mx.velora.api.importing.job;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import mx.velora.api.activity.ActivitySubType;
import mx.velora.api.activity.Metric;
import mx.velora.api.activity.Metric.Source;
import mx.velora.api.activity.NewActivity;
import mx.velora.api.activity.NewActivity.MetricValue;
import mx.velora.api.activity.StreamType;
import mx.velora.api.importing.DerivedMetrics;
import mx.velora.api.importing.ImportedActivity;
import mx.velora.api.importing.ReportedSummary;
import mx.velora.api.importing.TrackPoint;
import mx.velora.api.importing.ValidatedActivity;
import org.jspecify.annotations.Nullable;

/** Traduce el modelo de {@code importing} al comando de {@code activity} (sin dependencia inversa). */
final class ActivityMapper {

    private ActivityMapper() {}

    record Context(
            UUID userId,
            @Nullable UUID bikeId,
            @Nullable UUID deviceId,
            @Nullable String bikeType,
            @Nullable String titleOverride,
            ZoneId timeZone,
            NewActivity.OriginalFile file) {}

    static NewActivity toNewActivity(ValidatedActivity validated, DerivedMetrics derived, Context context) {
        var activity = validated.activity();
        var startedAt = validated.footprint().startedAt();
        var hasPositions = activity.points().stream().anyMatch(TrackPoint::hasPosition);
        return new NewActivity(
                context.userId(),
                context.bikeId(),
                context.deviceId(),
                context.titleOverride() != null ? context.titleOverride() : activity.title(),
                ActivitySubType.infer(hasPositions, activity.reported().subSport(), context.bikeType()),
                startedAt,
                context.timeZone(),
                activity.format().name(),
                activity.sourceActivityKey(),
                metrics(activity.reported(), derived),
                samples(activity, startedAt),
                activity.laps().stream()
                        .map(l -> new NewActivity.Lap(
                                l.lapNumber(),
                                l.startedAt(),
                                l.elapsedTimeSeconds(),
                                l.timerTimeSeconds(),
                                l.distanceMeters(),
                                l.averageSpeedMps(),
                                l.maxSpeedMps(),
                                l.averageHeartRateBpm(),
                                l.maxHeartRateBpm(),
                                l.averageCadenceRpm(),
                                l.averagePowerWatts(),
                                l.maxPowerWatts()))
                        .toList(),
                context.file());
    }

    static List<MetricValue> metrics(ReportedSummary r, DerivedMetrics d) {
        var metrics = new ArrayList<MetricValue>();
        reported(metrics, Metric.ELAPSED_TIME, r.elapsedTimeSeconds());
        // El tiempo con cronómetro activo del dispositivo es su tiempo en movimiento.
        reported(metrics, Metric.MOVING_TIME, r.timerTimeSeconds());
        reported(metrics, Metric.DISTANCE, r.distanceMeters());
        reported(metrics, Metric.ELEVATION_GAIN, r.ascentMeters());
        reported(metrics, Metric.ELEVATION_LOSS, r.descentMeters());
        reported(metrics, Metric.AVERAGE_SPEED, r.averageSpeedMps());
        reported(metrics, Metric.MAX_SPEED, r.maxSpeedMps());
        reported(metrics, Metric.AVERAGE_HEART_RATE, number(r.averageHeartRateBpm()));
        reported(metrics, Metric.MAX_HEART_RATE, number(r.maxHeartRateBpm()));
        reported(metrics, Metric.AVERAGE_CADENCE, number(r.averageCadenceRpm()));
        reported(metrics, Metric.AVERAGE_POWER, number(r.averagePowerWatts()));
        reported(metrics, Metric.MAX_POWER, number(r.maxPowerWatts()));

        var p = d.parameters();
        var parameters = Map.of(
                "movingSpeedThresholdMps", p.movingSpeedThresholdMps(),
                "maxMovingIntervalSeconds", p.maxMovingIntervalSeconds(),
                "elevationHysteresisMeters", p.elevationHysteresisMeters(),
                "maxSpeedWindowSeconds", p.maxSpeedWindowSeconds());
        Function<Metric, java.util.function.Consumer<@Nullable Double>> derivedOf = metric -> value -> {
            if (value != null) {
                metrics.add(new MetricValue(metric, Source.DERIVED, value, d.algorithmVersion(), parameters));
            }
        };
        derivedOf.apply(Metric.ELAPSED_TIME).accept(d.elapsedTimeSeconds());
        derivedOf.apply(Metric.MOVING_TIME).accept(d.movingTimeSeconds());
        derivedOf.apply(Metric.DISTANCE).accept(d.distanceMeters());
        derivedOf.apply(Metric.ELEVATION_GAIN).accept(d.ascentMeters());
        derivedOf.apply(Metric.ELEVATION_LOSS).accept(d.descentMeters());
        derivedOf.apply(Metric.AVERAGE_SPEED).accept(d.averageSpeedMps());
        derivedOf.apply(Metric.MAX_SPEED).accept(d.maxSpeedMps());
        derivedOf.apply(Metric.AVERAGE_HEART_RATE).accept(number(d.averageHeartRateBpm()));
        derivedOf.apply(Metric.MAX_HEART_RATE).accept(number(d.maxHeartRateBpm()));
        derivedOf.apply(Metric.AVERAGE_CADENCE).accept(number(d.averageCadenceRpm()));
        derivedOf.apply(Metric.AVERAGE_POWER).accept(number(d.averagePowerWatts()));
        derivedOf.apply(Metric.MAX_POWER).accept(number(d.maxPowerWatts()));
        return metrics;
    }

    static NewActivity.Samples samples(ImportedActivity activity, Instant startedAt) {
        var points = activity.points();
        var count = points.size();
        var segments = new int[count];
        var streams = new EnumMap<StreamType, @Nullable Double[]>(StreamType.class);
        column(
                streams,
                StreamType.TIME,
                points,
                p -> p.time() == null
                        ? null
                        : (double) Duration.between(startedAt, p.time()).toMillis());
        column(streams, StreamType.LATITUDE, points, TrackPoint::latitude);
        column(streams, StreamType.LONGITUDE, points, TrackPoint::longitude);
        column(streams, StreamType.ALTITUDE, points, TrackPoint::altitudeMeters);
        column(streams, StreamType.DISTANCE, points, TrackPoint::distanceMeters);
        column(streams, StreamType.SPEED, points, TrackPoint::speedMps);
        column(streams, StreamType.HEART_RATE, points, p -> number(p.heartRateBpm()));
        column(streams, StreamType.CADENCE, points, p -> number(p.cadenceRpm()));
        column(streams, StreamType.POWER, points, p -> number(p.powerWatts()));
        column(streams, StreamType.TEMPERATURE, points, TrackPoint::temperatureCelsius);
        for (int i = 0; i < count; i++) {
            segments[i] = points.get(i).segment();
        }
        return new NewActivity.Samples(count, segments, streams);
    }

    private static void column(
            Map<StreamType, @Nullable Double[]> streams,
            StreamType type,
            List<TrackPoint> points,
            Function<TrackPoint, @Nullable Double> field) {
        var values = new Double[points.size()];
        var any = false;
        for (int i = 0; i < values.length; i++) {
            values[i] = field.apply(points.get(i));
            any |= values[i] != null;
        }
        if (any) {
            streams.put(type, values);
        }
    }

    private static void reported(List<MetricValue> metrics, Metric metric, @Nullable Double value) {
        if (value != null) {
            metrics.add(new MetricValue(metric, Source.DEVICE_REPORTED, value, null, null));
        }
    }

    private static @Nullable Double number(@Nullable Integer value) {
        return value == null ? null : value.doubleValue();
    }
}
