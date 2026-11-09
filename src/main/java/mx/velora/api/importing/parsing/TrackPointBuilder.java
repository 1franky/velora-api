package mx.velora.api.importing.parsing;

import java.time.Instant;
import mx.velora.api.importing.TrackPoint;
import org.jspecify.annotations.Nullable;

/** Acumula los campos de una muestra mientras se recorre el XML. */
final class TrackPointBuilder {

    final int segment;

    @Nullable Instant time;

    @Nullable Double latitude;

    @Nullable Double longitude;

    @Nullable Double altitudeMeters;

    @Nullable Double distanceMeters;

    @Nullable Integer heartRateBpm;

    @Nullable Integer cadenceRpm;

    @Nullable Integer powerWatts;

    @Nullable Double speedMps;

    @Nullable Double temperatureCelsius;

    TrackPointBuilder(int segment) {
        this.segment = segment;
    }

    TrackPoint build() {
        return new TrackPoint(
                segment,
                time,
                latitude,
                longitude,
                altitudeMeters,
                distanceMeters,
                heartRateBpm,
                cadenceRpm,
                powerWatts,
                speedMps,
                temperatureCelsius);
    }
}
