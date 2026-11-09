package mx.velora.api.activity;

import java.util.UUID;

/** Evento de dominio: se recalcularon los datos derivados de una actividad desde su original. */
public record ActivityReprocessed(UUID activityId, UUID userId) {}
