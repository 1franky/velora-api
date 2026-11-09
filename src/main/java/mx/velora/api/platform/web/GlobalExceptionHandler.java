package mx.velora.api.platform.web;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduce excepciones a respuestas {@code application/problem+json} (RFC 9457).
 *
 * <p>Las excepciones propias de Spring MVC (400, 404, 405, 413, etc.) las resuelve
 * {@link ResponseEntityExceptionHandler}. Cualquier otra excepción se responde como 500 con un {@code errorId}
 * para correlacionar con los logs, sin exponer mensajes internos ni stack traces.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String ERROR_ID_PROPERTY = "errorId";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messages;

    GlobalExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        var errorId = UUID.randomUUID().toString();
        // Solo el tipo de excepción y el stack trace: el mensaje puede contener datos sensibles.
        log.error(
                "Error no controlado [errorId={}]: {}",
                errorId,
                exception.getClass().getName(),
                exception);

        var locale = LocaleContextHolder.getLocale();
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, messages.getMessage("problem.internal.detail", null, locale));
        problem.setTitle(messages.getMessage("problem.internal.title", null, locale));
        problem.setProperty(ERROR_ID_PROPERTY, errorId);
        return problem;
    }
}
