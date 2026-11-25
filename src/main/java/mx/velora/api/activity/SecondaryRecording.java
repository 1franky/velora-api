package mx.velora.api.activity;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Grabación secundaria ya procesada (ADR-032): el archivo de otro dispositivo, guardado junto a una actividad sin ser
 * una actividad. {@code activityId} es {@code null} mientras esté sin asignar.
 *
 * @param attachedBy {@code AUTO} o {@code USER}; {@code null} si está sin asignar
 * @param completeness {@code COMPLETE} o {@code PARTIAL}, según informó el dispositivo
 */
public record SecondaryRecording(
        UUID id,
        String source,
        UUID clientRecordingId,
        @Nullable UUID activityId,
        @Nullable String attachedBy,
        String completeness,
        String format,
        Instant startedAt,
        Instant endedAt,
        double elapsedTimeSeconds,
        @Nullable Double distanceMeters,
        int pointCount,
        @Nullable Integer averageHeartRateBpm,
        @Nullable Integer maxHeartRateBpm,
        Instant receivedAt,
        Original original) {

    /** Fuentes admitidas. */
    public static final String SOURCE_WATCH = "WATCH";

    public boolean assigned() {
        return activityId != null;
    }

    /** Referencia al original en {@code OriginalFileStore} (ADR-013). */
    public record Original(String storageKey, byte[] sha256, long sizeBytes) {

        public Original {
            sha256 = sha256.clone();
        }

        @Override
        public byte[] sha256() {
            return sha256.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Original o
                    && storageKey.equals(o.storageKey)
                    && java.util.Arrays.equals(sha256, o.sha256)
                    && sizeBytes == o.sizeBytes;
        }

        @Override
        public int hashCode() {
            return 31 * storageKey.hashCode() + java.util.Arrays.hashCode(sha256);
        }

        @Override
        public String toString() {
            return "Original[storageKey=" + storageKey + ", sizeBytes=" + sizeBytes + "]";
        }
    }
}
