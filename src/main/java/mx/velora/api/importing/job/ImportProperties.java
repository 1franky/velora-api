package mx.velora.api.importing.job;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de la importación ({@code velora.import.*}).
 *
 * @param worker ejecución del worker
 * @param maxAttempts intentos antes de marcar un trabajo como fallido
 * @param retryBaseDelay espera del primer reintento; se duplica en cada intento
 * @param maxPoints muestras máximas por archivo (ADR-009)
 */
@ConfigurationProperties("velora.import")
public record ImportProperties(
        @DefaultValue Worker worker,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("PT30S") Duration retryBaseDelay,
        @DefaultValue("200000") int maxPoints) {

    /**
     * @param enabled si el worker se ejecuta periódicamente (las pruebas lo invocan a mano)
     * @param pollInterval espera entre revisiones de la cola
     * @param batchSize trabajos tomados por revisión
     * @param lockDuration tiempo tras el cual un trabajo tomado y no terminado (p. ej. por un reinicio) se recupera
     */
    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT2S") Duration pollInterval,
            @DefaultValue("5") int batchSize,
            @DefaultValue("PT5M") Duration lockDuration) {}
}
