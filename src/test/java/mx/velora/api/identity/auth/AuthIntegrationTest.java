package mx.velora.api.identity.auth;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import mx.velora.api.IntegrationTest;
import mx.velora.api.contract.OpenApiContract;
import mx.velora.api.identity.user.OwnerBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AuthIntegrationTest {

    private static final String PASSWORD = "una-contraseña-larga-de-prueba";
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    private static final OpenApiInteractionValidator CONTRACT = OpenApiContract.validator();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerBootstrap bootstrap;

    /** Correo e IP únicos por prueba: el limitador de intentos es un singleton en memoria. */
    private String email;

    private String ip;
    private UUID ownerId;

    @BeforeEach
    void createOwner() {
        jdbc.sql("TRUNCATE users CASCADE").update();
        email = "propietario-" + UUID.randomUUID() + "@example.com";
        ip = "10.0.0." + NEXT_IP.getAndIncrement();
        ownerId = bootstrap.createOwner(email, PASSWORD);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private String loginBody(String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private String login() throws Exception {
        return mvc.perform(json(post("/api/v1/auth/login"), loginBody(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static String field(String json, String name) {
        return JsonPath.read(json, "$." + name);
    }

    private MockHttpServletRequestBuilder whoami(String accessToken) {
        return get("/api/v1/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    @Test
    void loginReturnsTokensMatchingTheContract() throws Exception {
        mvc.perform(json(post("/api/v1/auth/login"), loginBody(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
                .andExpect(jsonPath("$.refreshTokenExpiresIn").value(2_592_000));
    }

    @Test
    void emailIsCaseInsensitive() throws Exception {
        var body = "{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"" + PASSWORD + "\"}";

        mvc.perform(json(post("/api/v1/auth/login"), body)).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameSpanishProblem() throws Exception {
        mvc.perform(json(post("/api/v1/auth/login"), loginBody("incorrecta-pero-larga")))
                .andExpect(status().isUnauthorized())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.detail").value("Correo o contraseña incorrectos."));

        mvc.perform(json(
                        post("/api/v1/auth/login"),
                        "{\"email\":\"nadie@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Correo o contraseña incorrectos."));
    }

    @Test
    void invalidLoginBodyIsRejected() throws Exception {
        mvc.perform(json(post("/api/v1/auth/login"), "{\"email\":\"no-es-correo\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void repeatedFailuresAreThrottledEvenWithTheRightPassword() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/api/v1/auth/login"), loginBody("incorrecta-" + i)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(json(post("/api/v1/auth/login"), loginBody(PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.title").value("Demasiados intentos"));
    }

    @Test
    void protectedRoutesRequireAValidAccessToken() throws Exception {
        var tokens = login();

        mvc.perform(whoami(field(tokens, "accessToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ownerId.toString()));
        mvc.perform(get("/api/v1/test/whoami"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Inicia sesión para continuar."));
        mvc.perform(whoami("token-inventado")).andExpect(status().isUnauthorized());
        // Un refresh token no sirve como access token.
        mvc.perform(whoami(field(tokens, "refreshToken"))).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedRequestToUnknownRouteIsNotFound() throws Exception {
        var access = field(login(), "accessToken");

        mvc.perform(get("/api/v1/no-existe").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("No encontrado"));
    }

    @Test
    void expiredAccessTokenIsRejected() throws Exception {
        var access = field(login(), "accessToken");
        jdbc.sql("UPDATE auth_tokens SET expires_at = now() - interval '1 second' WHERE kind = 'ACCESS'")
                .update();

        mvc.perform(whoami(access)).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesAndReuseRevokesTheWholeSession() throws Exception {
        var first = login();
        var oldRefresh = field(first, "refreshToken");

        var second = mvc.perform(json(post("/api/v1/auth/refresh"), "{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(field(second, "refreshToken")).isNotEqualTo(oldRefresh);
        mvc.perform(whoami(field(second, "accessToken"))).andExpect(status().isOk());

        // Reutilizar el refresh ya rotado: posible robo.
        mvc.perform(json(post("/api/v1/auth/refresh"), "{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(openApi().isValid(CONTRACT))
                .andExpect(jsonPath("$.detail").value("La sesión expiró o no es válida. Inicia sesión de nuevo."));

        // La sesión completa quedó revocada.
        mvc.perform(whoami(field(second, "accessToken"))).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/v1/auth/refresh"), "{\"refreshToken\":\"" + field(second, "refreshToken") + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheSessionAndIsIdempotent() throws Exception {
        var tokens = login();
        var refreshBody = "{\"refreshToken\":\"" + field(tokens, "refreshToken") + "\"}";

        mvc.perform(json(post("/api/v1/auth/logout"), refreshBody)).andExpect(status().isNoContent());

        mvc.perform(whoami(field(tokens, "accessToken"))).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/v1/auth/refresh"), refreshBody)).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/v1/auth/logout"), refreshBody)).andExpect(status().isNoContent());
        mvc.perform(json(post("/api/v1/auth/logout"), "{\"refreshToken\":\"desconocido\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void anotherLoginIsAnIndependentSession() throws Exception {
        var phone = login();
        var laptop = login();

        mvc.perform(json(post("/api/v1/auth/logout"), "{\"refreshToken\":\"" + field(phone, "refreshToken") + "\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(whoami(field(laptop, "accessToken"))).andExpect(status().isOk());
    }

    @Test
    void passwordsAndTokensNeverReachTheLogs(CapturedOutput output) throws Exception {
        var tokens = login();
        var refresh = field(tokens, "refreshToken");
        mvc.perform(json(post("/api/v1/auth/refresh"), "{\"refreshToken\":\"" + refresh + "\"}"));
        mvc.perform(json(post("/api/v1/auth/refresh"), "{\"refreshToken\":\"" + refresh + "\"}"));
        mvc.perform(json(post("/api/v1/auth/login"), loginBody("incorrecta-pero-larga")));

        assertThat(output.getAll())
                .contains("Reutilización de refresh token detectada")
                .doesNotContain(PASSWORD)
                .doesNotContain("incorrecta-pero-larga")
                .doesNotContain(refresh)
                .doesNotContain(field(tokens, "accessToken"));
    }

    @Test
    void storesOnlyTokenHashes() throws Exception {
        var tokens = login();

        var stored = jdbc.sql("SELECT encode(token_hash, 'escape') FROM auth_tokens")
                .query(String.class)
                .list();
        assertThat(stored).noneMatch(value -> value.contains(field(tokens, "accessToken")));
        assertThat(jdbc.sql("SELECT count(*) FROM auth_tokens WHERE octet_length(token_hash) = 32")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }
}
