package mx.velora.api.importing;

import java.util.Objects;

/** Identifica el parser que produjo una actividad, para trazabilidad y reprocesamiento. */
public record ParserInfo(String name, String version) {

    public ParserInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
    }
}
