package mx.velora.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import mx.velora.api.identity.user.OwnerBootstrap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Crea un propietario limpio e inicia sesión para pruebas de endpoints protegidos. */
public final class TestSessions {

    public static final String PASSWORD = "una-contraseña-larga-de-prueba";

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    /** Sesión de prueba: usuario, correo único y access token. */
    public record Session(UUID userId, String email, String accessToken) {

        public MockHttpServletRequestBuilder authorize(MockHttpServletRequestBuilder request) {
            return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
    }

    private TestSessions() {}

    /** Vacía {@code users} (y en cascada todo lo que dependa de ellos), crea el propietario e inicia sesión. */
    public static Session freshOwner(MockMvc mvc, JdbcClient jdbc, OwnerBootstrap bootstrap) throws Exception {
        jdbc.sql("TRUNCATE users CASCADE").update();
        var email = "propietario-" + UUID.randomUUID() + "@example.com";
        var userId = bootstrap.createOwner(email, PASSWORD);
        var ip = "10.1." + (NEXT_IP.get() / 250) + "." + (NEXT_IP.getAndIncrement() % 250 + 1);
        var body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Session(userId, email, JsonPath.read(body, "$.accessToken"));
    }
}
