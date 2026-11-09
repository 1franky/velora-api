package mx.velora.api.importing;

/**
 * Recuadro geográfico en grados WGS84.
 *
 * <p>No contempla tracks que crucen el antimeridiano (±180°); quedan fuera del alcance del MVP.
 */
public record BoundingBox(double minLatitude, double minLongitude, double maxLatitude, double maxLongitude) {

    public BoundingBox {
        if (minLatitude > maxLatitude || minLongitude > maxLongitude) {
            throw new IllegalArgumentException("Los mínimos no pueden superar a los máximos");
        }
    }

    public boolean intersects(BoundingBox other) {
        return minLatitude <= other.maxLatitude
                && other.minLatitude <= maxLatitude
                && minLongitude <= other.maxLongitude
                && other.minLongitude <= maxLongitude;
    }
}
