package mx.velora.api.importing;

/** Motivo por el que un archivo se rechaza durante la detección o el parseo. */
public enum RejectionReason {
    /** El archivo no tiene contenido. */
    EMPTY_FILE,
    /** El contenido no corresponde a un formato admitido, o el formato aún no tiene parser. */
    UNSUPPORTED_FORMAT,
    /** El formato se reconoce, pero la estructura o los valores son inválidos. */
    MALFORMED_FILE,
    /** El archivo contiene construcciones XML prohibidas (DTD, entidades), p. ej. intentos de XXE. */
    FORBIDDEN_XML_CONSTRUCT,
    /** El archivo supera el número máximo de muestras permitido. */
    TOO_MANY_POINTS
}
