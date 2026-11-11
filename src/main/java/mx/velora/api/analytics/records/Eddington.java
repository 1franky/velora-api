package mx.velora.api.analytics.records;

import java.util.List;

/**
 * Número de Eddington (04-geospatial-and-analytics.md §6): el mayor entero {@code E} tal que hay al menos {@code E}
 * días distintos con al menos {@code E} km. Puro: recibe la distancia de cada día local, en kilómetros.
 */
final class Eddington {

    static final String ALGORITHM_VERSION = "eddington/1";

    /**
     * @param number el número de Eddington
     * @param daysForNext días que ya tienen al menos {@code number + 1} km
     * @param missingForNext días con {@code number + 1} km o más que faltan para subir a {@code number + 1}
     */
    record Result(int number, int daysForNext, int missingForNext) {}

    private Eddington() {}

    static Result of(List<Double> dailyKilometers) {
        var sorted = dailyKilometers.stream()
                .filter(km -> km > 0)
                .sorted((a, b) -> Double.compare(b, a))
                .toList();
        var number = 0;
        while (number < sorted.size() && sorted.get(number) >= number + 1) {
            number++;
        }
        var target = number + 1;
        var reached = (int) sorted.stream().filter(km -> km >= target).count();
        return new Result(number, reached, target - reached);
    }
}
