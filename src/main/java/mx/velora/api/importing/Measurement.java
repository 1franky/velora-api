package mx.velora.api.importing;

import java.util.Objects;

/** Valor numérico en unidades SI junto con su origen. */
public record Measurement(double value, MetricProvenance provenance) {

    public Measurement {
        Objects.requireNonNull(provenance, "provenance");
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value debe ser finito");
        }
    }
}
