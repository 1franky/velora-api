package mx.velora.api.importing;

import java.util.Objects;

/**
 * Rechazo de un archivo de actividad.
 *
 * <p>El mensaje es seguro para logs: contiene solo el motivo y, como mucho, la ubicación (línea) del problema.
 * Nunca incluye contenido del archivo (coordenadas, valores, texto). Por la misma razón no se encadena la causa
 * original, cuyo mensaje podría contener esos datos.
 */
public class ImportRejectedException extends RuntimeException {

    private final RejectionReason reason;

    public ImportRejectedException(RejectionReason reason, String safeDetail) {
        super(reason + ": " + safeDetail);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public RejectionReason reason() {
        return reason;
    }
}
