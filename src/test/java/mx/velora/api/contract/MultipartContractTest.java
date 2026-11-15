package mx.velora.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.report.ValidationReport;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** La validación multipart detecta lo que el contrato de POST /activities/imports prohíbe. */
class MultipartContractTest {

    private static final String IMPORTS = "/activities/imports";

    private static ValidationReport imports(Map<String, String[]> fields, Set<String> files) {
        return OpenApiContract.validateMultipart("POST", IMPORTS, fields, files);
    }

    @Test
    void acceptsTheFileAloneOrWithEveryDeclaredField() {
        assertThat(imports(Map.of(), Set.of("file")).getMessages()).isEmpty();
        assertThat(imports(
                                Map.of(
                                        "bikeId",
                                                new String[] {UUID.randomUUID().toString()},
                                        "deviceId",
                                                new String[] {UUID.randomUUID().toString()},
                                        "activityTitle", new String[] {"Rodada al Ajusco"}),
                                Set.of("file"))
                        .getMessages())
                .isEmpty();
    }

    @Test
    void rejectsWhatTheContractForbids() {
        assertThat(imports(Map.of(), Set.of()).hasErrors()).as("sin archivo").isTrue();
        assertThat(imports(Map.of("bikeId", new String[] {"no-es-uuid"}), Set.of("file"))
                        .hasErrors())
                .as("bikeId sin formato uuid")
                .isTrue();
        assertThat(imports(Map.of("activityTitle", new String[] {"x".repeat(201)}), Set.of("file"))
                        .hasErrors())
                .as("título de más de 200")
                .isTrue();
        assertThat(imports(Map.of("titulo", new String[] {"campo no declarado"}), Set.of("file"))
                        .hasErrors())
                .as("campo no declarado")
                .isTrue();
    }

    @Test
    void anOperationWithoutMultipartBodyIsReported() {
        assertThat(OpenApiContract.validateMultipart("POST", "/auth/login", Map.of(), Set.of("file"))
                        .hasErrors())
                .isTrue();
        assertThat(OpenApiContract.validateMultipart("POST", "/no-existe", Map.of(), Set.of())
                        .hasErrors())
                .isTrue();
    }
}
