package mx.velora.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import org.junit.jupiter.api.Test;

/**
 * Verifica que el contrato se carga sin errores y que sus esquemas aceptan y rechazan lo esperado, incluidas
 * construcciones de OpenAPI 3.1 como {@code type: [string, 'null']}.
 */
class OpenApiContractTest {

    private static final String BEARER = "Bearer token-de-prueba";

    private final OpenApiInteractionValidator validator = OpenApiContract.validator();

    private ValidationReport response(String method, String path, int status, String contentType, String body) {
        var request = new SimpleRequest.Builder(method, path)
                .withHeader("Authorization", BEARER)
                .build();
        var response = SimpleResponse.Builder.status(status)
                .withContentType(contentType)
                .withBody(body)
                .build();
        return validator.validateResponse(request.getPath(), request.getMethod(), response);
    }

    private ValidationReport request(String method, String path, String body) {
        var request = new SimpleRequest.Builder(method, path)
                .withHeader("Authorization", BEARER)
                .withContentType("application/json")
                .withBody(body)
                .build();
        return validator.validateRequest(request);
    }

    @Test
    void acceptsValidTokenResponse() {
        var report = response("POST", "/api/v1/auth/login", 200, "application/json", """
                {"tokenType":"Bearer","accessToken":"a","accessTokenExpiresIn":900,
                 "refreshToken":"r","refreshTokenExpiresIn":2592000}
                """);

        assertThat(report.getMessages()).isEmpty();
    }

    @Test
    void rejectsTokenResponseWithoutRefreshToken() {
        var report = response("POST", "/api/v1/auth/login", 200, "application/json", """
                {"tokenType":"Bearer","accessToken":"a","accessTokenExpiresIn":900,"refreshTokenExpiresIn":1}
                """);

        assertSchemaError(report);
    }

    @Test
    void acceptsNullableFieldsDeclaredWithOpenApi31TypeArrays() {
        var report = response("GET", "/api/v1/bikes", 200, "application/json", """
                {"items":[{"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","name":"Rígida","bikeType":"MTB",
                  "brand":null,"model":null,"weightKg":null,"active":true,"createdAt":"2026-11-09T18:00:00Z"}]}
                """);

        assertThat(report.getMessages()).isEmpty();
    }

    @Test
    void rejectsUnknownBikeType() {
        var report = response("GET", "/api/v1/bikes", 200, "application/json", """
                {"items":[{"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","name":"X","bikeType":"BMX",
                  "brand":null,"model":null,"weightKg":null,"active":true,"createdAt":"2026-11-09T18:00:00Z"}]}
                """);

        assertSchemaError(report);
    }

    @Test
    void rejectsWrongTypeInNullableField() {
        var report = response("GET", "/api/v1/bikes", 200, "application/json", """
                {"items":[{"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","name":"X","bikeType":"MTB",
                  "brand":null,"model":null,"weightKg":"pesada","active":true,"createdAt":"2026-11-09T18:00:00Z"}]}
                """);

        assertSchemaError(report);
    }

    @Test
    void acceptsProblemDetailsForUnauthorized() {
        var report = response("GET", "/api/v1/me", 401, "application/problem+json", """
                {"type":"about:blank","title":"No autenticado","status":401}
                """);

        assertThat(report.getMessages()).isEmpty();
    }

    @Test
    void rejectsBikeCreateWithUnknownProperty() {
        var report = request("POST", "/api/v1/bikes", """
                {"name":"Ruta","bikeType":"ROAD","color":"rojo"}
                """);

        assertSchemaError(report);
    }

    @Test
    void rejectsBikeCreateWithNonPositiveWeight() {
        var report = request("POST", "/api/v1/bikes", """
                {"name":"Ruta","bikeType":"ROAD","weightKg":0}
                """);

        assertSchemaError(report);
    }

    @Test
    void acceptsMinimalBikeCreate() {
        var report = request("POST", "/api/v1/bikes", """
                {"name":"Ruta","bikeType":"ROAD"}
                """);

        assertThat(report.getMessages()).isEmpty();
    }

    @Test
    void rejectsEmptyPreferencesUpdate() {
        var report = request("PATCH", "/api/v1/me/preferences", "{}");

        assertSchemaError(report);
    }

    /** El error debe venir del esquema del cuerpo, no de ruta, método o seguridad. */
    private static void assertSchemaError(ValidationReport report) {
        assertThat(report.getMessages())
                .isNotEmpty()
                .allSatisfy(message -> assertThat(message.getKey()).contains(".body.schema."));
    }
}
