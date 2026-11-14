package mx.velora.api.platform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler(messages))
                .setLocaleResolver(new FixedLocaleResolver(Locale.forLanguageTag("es-MX")))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void unexpectedErrorReturnsProblemDetailInSpanishWithoutInternalDetails() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Error interno"))
                .andExpect(jsonPath("$.detail").value(Matchers.startsWith("Ocurrió un error inesperado")))
                .andExpect(jsonPath("$.errorId").isNotEmpty())
                .andExpect(content().string(Matchers.not(Matchers.containsString("secreto"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("IllegalStateException"))));
    }

    @Test
    void errorIdIsTheRequestIdSoEveryLogLineOfTheRequestCanBeFound() throws Exception {
        mvc.perform(get("/boom").header(RequestIdFilter.HEADER, "web-1234abcd"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(RequestIdFilter.HEADER, "web-1234abcd"))
                .andExpect(jsonPath("$.errorId").value("web-1234abcd"));
    }

    @Test
    void requestIdIsGeneratedWhenMissingOrUnsafe() throws Exception {
        var generated = mvc.perform(get("/boom")).andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
        org.assertj.core.api.Assertions.assertThat(generated).matches("[0-9a-f-]{36}");

        for (var unsafe : new String[] {"corto", "x".repeat(65), "con espacio 12345", "inyección\nlog"}) {
            mvc.perform(get("/boom").header(RequestIdFilter.HEADER, unsafe))
                    .andExpect(header().string(RequestIdFilter.HEADER, Matchers.matchesPattern("[0-9a-f-]{36}")));
        }
    }

    @RestController
    static class FailingController {
        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("dato secreto que no debe filtrarse");
        }
    }
}
