package mx.velora.api.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import mx.velora.api.identity.AuthenticatedUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/me/export} y {@code /api/v1/me/deletion} según {@code openapi/openapi-v1.yaml}. */
@RestController
@RequestMapping("/api/v1/me")
class AccountController {

    record DeletionRequest(@NotBlank @Size(max = 1024) String password) {}

    private final AccountExport export;
    private final AccountDeletion deletion;
    private final Clock clock;

    AccountController(AccountExport export, AccountDeletion deletion, Clock clock) {
        this.export = export;
        this.deletion = deletion;
        this.clock = clock;
    }

    /**
     * Escribe el ZIP de forma síncrona en la respuesta: un {@code StreamingResponseBody} quedaría sujeto al timeout
     * de las solicitudes asíncronas y cortaría exportaciones grandes.
     */
    @GetMapping("/export")
    void exportData(@AuthenticationPrincipal AuthenticatedUser user, HttpServletResponse response) throws IOException {
        export.acquire(user.userId());
        try {
            var date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            response.setContentType("application/zip");
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"velora-export-" + date + ".zip\"");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            export.write(user.userId(), response.getOutputStream());
        } finally {
            export.release(user.userId());
        }
    }

    @PostMapping(path = "/deletion", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAccount(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody DeletionRequest request,
            HttpServletRequest http) {
        deletion.delete(user.userId(), request.password(), http.getRemoteAddr());
    }
}
