package mx.velora.api.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/auth/*} según {@code openapi/openapi-v1.yaml}. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 1024) String password) {

        @Override
        public String toString() {
            return "LoginRequest[redactado]";
        }
    }

    record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[redactado]";
        }
    }

    record TokenResponse(
            String tokenType,
            String accessToken,
            long accessTokenExpiresIn,
            String refreshToken,
            long refreshTokenExpiresIn) {

        static TokenResponse of(AuthService.TokenPair pair) {
            return new TokenResponse(
                    "Bearer",
                    pair.accessToken(),
                    pair.accessTokenExpiresIn(),
                    pair.refreshToken(),
                    pair.refreshTokenExpiresIn());
        }

        @Override
        public String toString() {
            return "TokenResponse[redactado]";
        }
    }

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return TokenResponse.of(auth.login(request.email(), request.password(), http.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.of(auth.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
    }
}
