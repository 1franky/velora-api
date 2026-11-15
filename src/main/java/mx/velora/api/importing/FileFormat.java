package mx.velora.api.importing;

/** Formatos de archivo de actividad admitidos. Se detectan por contenido, nunca por extensión. */
public enum FileFormat {
    FIT,
    GPX,
    TCX,
    /** Documento {@link StravaActivityDocument}; solo para trabajos creados por la sincronización (ADR-030). */
    STRAVA
}
