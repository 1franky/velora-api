package mx.velora.api.identity.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** Errores de preferencias; textos en {@code messages.properties} (es-MX). */
final class PreferencesProblems {

    private PreferencesProblems() {}

    static final class EmptyPreferencesUpdateException extends ErrorResponseException {
        EmptyPreferencesUpdateException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }

    static final class InvalidTimeZoneException extends ErrorResponseException {
        InvalidTimeZoneException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }

    static final class UnsupportedUnitException extends ErrorResponseException {
        UnsupportedUnitException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }
}
