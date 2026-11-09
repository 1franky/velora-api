package mx.velora.api.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import java.nio.file.Path;

/** Validador compartido del contrato {@code openapi/openapi-v1.yaml}, fuente de verdad de la API. */
public final class OpenApiContract {

    public static final Path SPEC = Path.of("openapi", "openapi-v1.yaml");

    private OpenApiContract() {}

    /** Falla al construirse si el contrato no es un documento OpenAPI válido. */
    public static OpenApiInteractionValidator validator() {
        return OpenApiInteractionValidator.createForSpecificationUrl(
                        SPEC.toAbsolutePath().toUri().toString())
                .withBasePathOverride("/api/v1")
                .build();
    }
}
