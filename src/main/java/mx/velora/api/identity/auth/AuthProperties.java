package mx.velora.api.identity.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de autenticación ({@code velora.auth.*}).
 *
 * @param accessTokenTtl vigencia del access token
 * @param refreshTokenTtl vigencia de cada refresh token
 * @param throttle límites de intentos de login
 */
@ConfigurationProperties("velora.auth")
public record AuthProperties(
        @DefaultValue("PT15M") Duration accessTokenTtl,
        @DefaultValue("P30D") Duration refreshTokenTtl,
        @DefaultValue Throttle throttle) {

    /**
     * @param freeFailures fallos permitidos antes de imponer espera
     * @param window ventana tras la cual se olvidan los fallos si no hay bloqueo activo
     * @param baseDelay primera espera; se duplica con cada fallo adicional
     * @param maxDelay espera máxima
     */
    public record Throttle(
            @DefaultValue("5") int freeFailures,
            @DefaultValue("PT15M") Duration window,
            @DefaultValue("PT30S") Duration baseDelay,
            @DefaultValue("PT15M") Duration maxDelay) {}
}
