package mx.velora.api.account;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** Errores del módulo account; título y detalle en {@code messages.properties}. */
final class AccountProblems {

    private AccountProblems() {}

    /** Ya hay una exportación del mismo usuario en curso. */
    static final class ExportInProgressException extends ErrorResponseException {
        ExportInProgressException() {
            super(HttpStatus.CONFLICT);
        }
    }
}
