package mx.velora.api.activity;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Comando para registrar una actividad ya validada. Usa tipos propios de {@code activity}: {@code importing} lo
 * construye a partir de su modelo, y así {@code activity} no depende de {@code importing}.
 *
 * @param userId propietario
 * @param bikeId bicicleta elegida al importar
 * @param deviceId dispositivo elegido al importar
 * @param title título del archivo o del usuario; si falta, se genera "Rodada del dd/MM/yyyy"
 * @param subType ver {@link ActivitySubType#infer}
 * @param startedAt instante UTC de inicio
 * @param timeZone zona horaria del usuario al importar; define la fecha deportiva local
 * @param sourceType {@code FIT}, {@code GPX} o {@code TCX}
 * @param sourceActivityKey clave de origen para deduplicación, si el formato la provee
 * @param metrics métricas por origen; deben incluir al menos tiempo transcurrido y en movimiento
 * @param samples streams por muestra
 * @param laps vueltas del dispositivo
 * @param file archivo original ya guardado
 */
public record NewActivity(
        UUID userId,
        @Nullable UUID bikeId,
        @Nullable UUID deviceId,
        @Nullable String title,
        ActivitySubType subType,
        Instant startedAt,
        ZoneId timeZone,
        String sourceType,
        @Nullable String sourceActivityKey,
        List<MetricValue> metrics,
        Samples samples,
        List<Lap> laps,
        OriginalFile file) {

    public NewActivity {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(subType, "subType");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(timeZone, "timeZone");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(file, "file");
        metrics = List.copyOf(metrics);
        laps = List.copyOf(laps);
    }

    /**
     * @param metric métrica
     * @param source origen
     * @param value valor en unidades SI
     * @param algorithmVersion obligatoria si {@code source} es {@code DERIVED}
     * @param parameters parámetros del cálculo (solo derivadas)
     */
    public record MetricValue(
            Metric metric,
            Metric.Source source,
            double value,
            @Nullable String algorithmVersion,
            @Nullable Map<String, Double> parameters) {

        public MetricValue {
            Objects.requireNonNull(metric, "metric");
            Objects.requireNonNull(source, "source");
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("value debe ser finito");
            }
            if ((source == Metric.Source.DERIVED) != (algorithmVersion != null)) {
                throw new IllegalArgumentException("Las métricas derivadas, y solo ellas, llevan algorithmVersion");
            }
            parameters = parameters == null ? null : Map.copyOf(parameters);
        }
    }

    /**
     * Muestras en columnas paralelas de longitud {@code count}; {@code null} en una posición significa ausente.
     *
     * @param count número de muestras
     * @param segments segmento de cada muestra (las pausas separan tramos del track)
     * @param streams columnas presentes; {@link StreamType#TIME} en milisegundos desde el inicio
     */
    public record Samples(int count, int[] segments, Map<StreamType, @Nullable Double[]> streams) {

        public Samples {
            if (segments.length != count) {
                throw new IllegalArgumentException("segments debe tener " + count + " elementos");
            }
            segments = segments.clone();
            var copy = new EnumMap<StreamType, @Nullable Double[]>(StreamType.class);
            streams.forEach((type, values) -> {
                if (values.length != count) {
                    throw new IllegalArgumentException(type + " debe tener " + count + " elementos");
                }
                copy.put(type, values.clone());
            });
            streams = copy;
        }

        @Override
        public int[] segments() {
            return segments.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Samples that
                    && count == that.count
                    && Arrays.equals(segments, that.segments)
                    && streams.keySet().equals(that.streams.keySet())
                    && streams.entrySet().stream()
                            .allMatch(e -> Arrays.equals(e.getValue(), that.streams.get(e.getKey())));
        }

        @Override
        public int hashCode() {
            return Objects.hash(count, Arrays.hashCode(segments), streams.keySet());
        }

        @Override
        public String toString() {
            return "Samples[" + count + " muestras, " + streams.keySet() + "]";
        }
    }

    public record Lap(
            int lapNumber,
            @Nullable Instant startedAt,
            @Nullable Double elapsedTimeSeconds,
            @Nullable Double timerTimeSeconds,
            @Nullable Double distanceMeters,
            @Nullable Double averageSpeedMps,
            @Nullable Double maxSpeedMps,
            @Nullable Integer averageHeartRateBpm,
            @Nullable Integer maxHeartRateBpm,
            @Nullable Integer averageCadenceRpm,
            @Nullable Integer averagePowerWatts,
            @Nullable Integer maxPowerWatts) {}

    /**
     * @param storageKey clave en {@code OriginalFileStore}
     * @param sha256 hash del archivo (32 bytes)
     * @param sizeBytes tamaño
     * @param parserName parser que lo leyó
     * @param parserVersion versión del parser
     */
    public record OriginalFile(
            String storageKey, byte[] sha256, long sizeBytes, String parserName, String parserVersion) {

        public OriginalFile {
            Objects.requireNonNull(storageKey, "storageKey");
            sha256 = sha256.clone();
            if (sha256.length != 32) {
                throw new IllegalArgumentException("sha256 debe tener 32 bytes");
            }
        }

        @Override
        public byte[] sha256() {
            return sha256.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof OriginalFile that
                    && storageKey.equals(that.storageKey)
                    && Arrays.equals(sha256, that.sha256)
                    && sizeBytes == that.sizeBytes;
        }

        @Override
        public int hashCode() {
            return storageKey.hashCode();
        }

        @Override
        public String toString() {
            return "OriginalFile[" + storageKey + "]";
        }
    }
}
