package mx.velora.api.geospatial.newterritory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Tramos recorridos por primera vez (04-geospatial-and-analytics.md §10, ADR-019). Puro: recibe las muestras del
 * camino ya marcadas y decide qué tramos son nuevos, cuánto miden y qué tan confiable es el resultado.
 */
final class NewTerritory {

    static final String ALGORITHM_VERSION = "new-territory/1";
    /** Un tramo nuevo más corto que esto se considera conocido: suele ser desplazamiento del GPS en un borde. */
    static final double MIN_STRETCH_METERS = 50;

    enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    /**
     * Muestra del camino. {@code fresh}: ninguna rodada anterior pasa a menos de la tolerancia. {@code nearMiss}:
     * nueva, pero alguna pasa a menos del doble (un camino paralelo o GPS desviado: resultado dudoso).
     */
    record Sample(int part, double lat, double lon, boolean fresh, boolean nearMiss) {}

    record Result(double analyzedMeters, double newMeters, Confidence confidence, List<List<double[]>> stretches) {

        double newFraction() {
            return analyzedMeters == 0 ? 0 : newMeters / analyzedMeters;
        }
    }

    private NewTerritory() {}

    /**
     * @param samples muestras en orden, agrupadas por tramo continuo ({@code part})
     * @param sampleSpacingMeters separación media entre puntos del archivo original (densidad del GPS)
     */
    static Result of(List<Sample> samples, double sampleSpacingMeters) {
        var analyzed = 0.0;
        var fresh = 0.0;
        var ambiguous = 0.0;
        var stretches = new ArrayList<List<double[]>>();
        List<double[]> current = null;
        var currentLength = 0.0;
        var currentAmbiguous = 0.0;
        Sample previous = null;
        for (var sample : samples) {
            var sameRun = previous != null && previous.part() == sample.part();
            var step = sameRun ? haversine(previous.lat(), previous.lon(), sample.lat(), sample.lon()) : 0;
            analyzed += step;
            var extendsStretch = sameRun && previous.fresh() && sample.fresh();
            if (!extendsStretch) {
                if (current != null && currentLength >= MIN_STRETCH_METERS) {
                    stretches.add(current);
                    fresh += currentLength;
                    ambiguous += currentAmbiguous;
                }
                current = null;
                currentLength = 0;
                currentAmbiguous = 0;
            }
            if (sample.fresh()) {
                if (current == null) {
                    current = new ArrayList<>();
                }
                current.add(new double[] {round(sample.lon()), round(sample.lat())});
                // El paso que viene de un punto conocido no es nuevo: solo cuenta el que une dos puntos nuevos.
                if (extendsStretch) {
                    currentLength += step;
                    if (previous.nearMiss() || sample.nearMiss()) {
                        currentAmbiguous += step;
                    }
                }
            }
            previous = sample;
        }
        if (current != null && currentLength >= MIN_STRETCH_METERS) {
            stretches.add(current);
            fresh += currentLength;
            ambiguous += currentAmbiguous;
        }
        return new Result(
                analyzed, fresh, confidence(fresh == 0 ? 0 : ambiguous / fresh, sampleSpacingMeters), stretches);
    }

    /**
     * Alta: GPS denso (≤ 15 m entre puntos) y menos del 10 % de lo nuevo pegado a caminos conocidos. Baja: GPS
     * escaso (> 40 m) o más del 40 % dudoso. Media en otro caso.
     */
    static Confidence confidence(double ambiguousFraction, double sampleSpacingMeters) {
        if (sampleSpacingMeters > 40 || ambiguousFraction > 0.4) {
            return Confidence.LOW;
        }
        if (sampleSpacingMeters <= 15 && ambiguousFraction < 0.1) {
            return Confidence.HIGH;
        }
        return Confidence.MEDIUM;
    }

    /** MultiLineString GeoJSON de los tramos nuevos; {@code null} si no hay. */
    static @Nullable String geometryJson(List<List<double[]>> stretches) {
        if (stretches.isEmpty()) {
            return null;
        }
        var json = new StringBuilder("{\"type\":\"MultiLineString\",\"coordinates\":[");
        for (int i = 0; i < stretches.size(); i++) {
            json.append(i == 0 ? "[" : ",[");
            var line = stretches.get(i);
            for (int j = 0; j < line.size(); j++) {
                json.append(j == 0 ? "" : ",")
                        .append(String.format(Locale.ROOT, "[%.6f,%.6f]", line.get(j)[0], line.get(j)[1]));
            }
            json.append(']');
        }
        return json.append("]}").toString();
    }

    private static double round(double value) {
        return Math.round(value * 1e6) / 1e6;
    }

    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        var rad = Math.PI / 180;
        var dLat = (lat2 - lat1) * rad;
        var dLon = (lon2 - lon1) * rad;
        var h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 6_371_008.8 * Math.asin(Math.sqrt(h));
    }
}
