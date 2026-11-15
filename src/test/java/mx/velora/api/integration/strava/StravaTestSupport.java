package mx.velora.api.integration.strava;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import mx.velora.api.TestSessions;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

/** Conecta Strava por el flujo real (autorización + retorno) contra {@link FakeStravaAuth}. */
final class StravaTestSupport {

    private StravaTestSupport() {}

    static void connect(MockMvc mvc, TestSessions.Session session) throws Exception {
        var body = mvc.perform(session.authorize(post("/api/v1/integrations/strava/authorization")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var state = UriComponentsBuilder.fromUriString(JsonPath.read(body, "$.authorizationUrl"))
                .build()
                .getQueryParams()
                .getFirst("state");
        mvc.perform(session.authorize(post("/api/v1/integrations/strava/connection"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bueno\",\"state\":\"" + state
                                + "\",\"scope\":\"read,activity:read_all\"}"))
                .andExpect(status().isOk());
    }
}
