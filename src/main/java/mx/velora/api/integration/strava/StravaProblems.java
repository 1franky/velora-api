package mx.velora.api.integration.strava;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** Errores del conector de Strava; título y detalle en {@code messages.properties}. */
final class StravaProblems {

    private StravaProblems() {}

    /** El conector está apagado ({@code velora.strava.enabled=false}). */
    static final class StravaDisabledException extends ErrorResponseException {
        StravaDisabledException() {
            super(HttpStatus.NOT_FOUND);
        }
    }

    /** Estado OAuth inválido, vencido o ya usado, o código rechazado por Strava. */
    static final class InvalidAuthorizationException extends ErrorResponseException {
        InvalidAuthorizationException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }

    /** No se concedió permiso para leer todas las actividades. */
    static final class MissingScopeException extends ErrorResponseException {
        MissingScopeException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }

    /** El atleta de Strava ya está conectado a otro usuario de Velora. */
    static final class AthleteAlreadyLinkedException extends ErrorResponseException {
        AthleteAlreadyLinkedException() {
            super(HttpStatus.CONFLICT);
        }
    }

    /** Strava no respondió; se puede reintentar. */
    static final class StravaUnavailableException extends ErrorResponseException {
        StravaUnavailableException() {
            super(HttpStatus.BAD_GATEWAY);
        }
    }
}
