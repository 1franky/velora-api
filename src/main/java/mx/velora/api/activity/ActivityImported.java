package mx.velora.api.activity;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio: se registró una actividad nueva ({@code 01-architecture.md}). Los módulos geoespacial y de
 * analítica reaccionarán a él.
 */
public record ActivityImported(UUID activityId, UUID userId, Instant startedAt) {}
