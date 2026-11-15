package mx.velora.api.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.MockMvcResponse;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.report.MessageResolver;
import com.atlassian.oai.validator.report.SimpleValidationReportFormat;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.schema.SchemaValidator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.json.JsonMapper;

/** Validador compartido del contrato {@code openapi/openapi-v1.yaml}, fuente de verdad de la API. */
public final class OpenApiContract {

    public static final Path SPEC = Path.of("openapi", "openapi-v1.yaml");

    private static final String BASE_PATH = "/api/v1";
    private static final String MULTIPART = "multipart/form-data";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OpenApiContract() {}

    /** Falla al construirse si el contrato no es un documento OpenAPI válido. */
    public static OpenApiInteractionValidator validator() {
        return OpenApiInteractionValidator.createForSpecificationUrl(
                        SPEC.toAbsolutePath().toUri().toString())
                .withBasePathOverride(BASE_PATH)
                .build();
    }

    /**
     * Valida una solicitud {@code multipart/form-data} contra el esquema de su operación. El validador de Atlassian
     * solo entiende formularios {@code x-www-form-urlencoded}: aquí cada campo se vuelve una propiedad de texto y
     * cada archivo un texto cualquiera (su esquema es {@code format: binary}), y el objeto resultante se valida con
     * el mismo validador de esquemas. Así se revisan requeridos, formatos, longitudes y campos no declarados.
     *
     * @param path ruta del contrato, sin {@code /api/v1}
     */
    public static ValidationReport validateMultipart(
            String method, String path, Map<String, String[]> fields, Set<String> files) {
        var item = Model.API.getPaths().get(path);
        if (item == null) {
            return error("La ruta " + path + " no está en el contrato");
        }
        var operation = item.readOperationsMap().get(PathItem.HttpMethod.valueOf(method));
        if (operation == null
                || operation.getRequestBody() == null
                || operation.getRequestBody().getContent().get(MULTIPART) == null) {
            return error(method + " " + path + " no declara un cuerpo " + MULTIPART);
        }
        var body = new LinkedHashMap<String, Object>();
        fields.forEach((name, values) -> body.put(name, values.length == 1 ? values[0] : List.of(values)));
        files.forEach(name -> body.put(name, "(archivo)"));
        return Model.SCHEMAS.validate(
                JSON.writeValueAsString(body),
                operation.getRequestBody().getContent().get(MULTIPART).getSchema(),
                "request.body");
    }

    /** Para MockMvc: valida el cuerpo multipart de la solicitud y la respuesta contra el contrato. */
    public static ResultMatcher multipartIsValid(OpenApiInteractionValidator validator) {
        return result -> {
            if (!(result.getRequest() instanceof MockMultipartHttpServletRequest request)) {
                throw new AssertionError("La solicitud no es multipart");
            }
            var uri = request.getRequestURI();
            var report = validateMultipart(
                            request.getMethod(),
                            uri.substring(BASE_PATH.length()),
                            request.getParameterMap(),
                            request.getMultiFileMap().keySet())
                    .merge(validator.validateResponse(
                            uri,
                            Request.Method.valueOf(request.getMethod()),
                            MockMvcResponse.of(result.getResponse())));
            if (report.hasErrors()) {
                throw new AssertionError("Fuera de contrato:\n"
                        + SimpleValidationReportFormat.getInstance().apply(report));
            }
        };
    }

    private static ValidationReport error(String message) {
        return ValidationReport.singleton(
                ValidationReport.Message.create("velora.contract", message).build());
    }

    /** Se carga al primer uso. */
    private static final class Model {
        static final OpenAPI API;
        static final SchemaValidator SCHEMAS;

        static {
            var options = new ParseOptions();
            options.setResolve(true);
            API = new OpenAPIV3Parser().read(SPEC.toAbsolutePath().toUri().toString(), null, options);
            SCHEMAS = new SchemaValidator(API, new MessageResolver());
        }
    }
}
