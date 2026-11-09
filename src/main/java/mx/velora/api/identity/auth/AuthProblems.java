package mx.velora.api.identity.auth;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * Errores de autenticación como {@link ErrorResponseException}: Spring MVC los convierte en Problem Details y toma
 * título y detalle de {@code messages.properties} ({@code problemDetail[.title].<clase>}), en es-MX.
 */
public final class AuthProblems {

    private AuthProblems() {}

    /** Correo o contraseña incorrectos. El mismo mensaje para correo inexistente, para no revelar cuentas. */
    public static final class InvalidCredentialsException extends ErrorResponseException {
        public InvalidCredentialsException() {
            super(HttpStatus.UNAUTHORIZED);
        }
    }

    /** Refresh token desconocido, expirado, revocado o reutilizado. */
    public static final class InvalidRefreshTokenException extends ErrorResponseException {
        public InvalidRefreshTokenException() {
            super(HttpStatus.UNAUTHORIZED);
        }
    }

    /** Solicitud sin access token válido a un recurso protegido. */
    public static final class UnauthenticatedException extends ErrorResponseException {
        public UnauthenticatedException() {
            super(HttpStatus.UNAUTHORIZED);
        }
    }

    /** Usuario autenticado sin permiso para la operación. */
    public static final class AccessDeniedProblemException extends ErrorResponseException {
        public AccessDeniedProblemException() {
            super(HttpStatus.FORBIDDEN);
        }
    }

    /** Demasiados intentos de login; incluye {@code Retry-After} en segundos (mínimo 1). */
    public static final class TooManyLoginAttemptsException extends ErrorResponseException {
        public TooManyLoginAttemptsException(Duration retryAfter) {
            super(HttpStatus.TOO_MANY_REQUESTS);
            var seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
            getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }
    }
}
