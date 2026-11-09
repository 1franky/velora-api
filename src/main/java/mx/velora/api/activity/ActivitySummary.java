package mx.velora.api.activity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Resumen de una actividad para listados y dashboard (contrato {@code ActivitySummary}). */
public record ActivitySummary(
        UUID id,
        String title,
        Instant startedAt,
        LocalDate localStartDate,
        ActivitySubType subType,
        @Nullable BigDecimal distanceMeters,
        long movingTimeSeconds,
        long elapsedTimeSeconds,
        @Nullable BigDecimal elevationGainMeters,
        @Nullable BigDecimal averageSpeedMps,
        @Nullable UUID bikeId) {}
