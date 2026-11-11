package mx.velora.api.identity;

import java.util.UUID;

/** Evento de dominio: el usuario cambió la privacidad de inicio y fin (ADR-016); los derivados se recalculan. */
public record TrackPrivacyChanged(UUID userId, int meters) {}
