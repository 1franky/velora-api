package mx.velora.api.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import mx.velora.api.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * El límite de intentos por IP usa la IP real del cliente, tomada de X-Forwarded-For cuando la conexión llega de un
 * proxy interno (túnel de Cloudflare o BFF de velora-web). Usa el servidor real: MockMvc no ejecuta RemoteIpValve.
 */
@IntegrationTest
class ClientAddressIntegrationTest {

    /** Tope de intentos para provocar la espera; holgado respecto a velora.auth.throttle.free-failures. */
    private static final int MAX_ATTEMPTS = 20;

    private static final AtomicInteger NEXT_OCTET = new AtomicInteger(1);

    @LocalServerPort
    int port;

    private RestClient http;

    /** IP de documentación (TEST-NET-3) distintas por prueba: el limitador es un singleton en memoria. */
    private String blockedIp;

    private String otherIp;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
        blockedIp = "203.0.113." + NEXT_OCTET.getAndIncrement();
        otherIp = "203.0.113." + NEXT_OCTET.getAndIncrement();
    }

    /** Cada intento usa un correo distinto: solo se acumulan fallos en la clave de la IP. */
    private int failedLogin(String forwardedFor) {
        var body = "{\"email\":\"nadie-" + UUID.randomUUID() + "@example.com\",\"password\":\"incorrecta\"}";
        return http.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", forwardedFor)
                .body(body)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value();
    }

    /** Falla desde una IP hasta que el limitador la bloquea. */
    private void lockOut(String forwardedFor) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            var status = failedLogin(forwardedFor);
            if (status == 429) {
                return;
            }
            assertThat(status).isEqualTo(401);
        }
        throw new AssertionError("La IP " + forwardedFor + " no se bloqueó tras " + MAX_ATTEMPTS + " intentos");
    }

    @Test
    void eachForwardedClientHasItsOwnLimit() {
        lockOut(blockedIp);

        assertThat(failedLogin(otherIp)).isEqualTo(401);
    }

    @Test
    void addressesAddedByTheClientAreIgnored() {
        lockOut(blockedIp);

        // Cloudflare agrega la IP real al final; lo que el cliente ponga antes no cambia la clave.
        assertThat(failedLogin(otherIp + ", " + blockedIp)).isEqualTo(429);
    }

    @Test
    void internalProxiesInTheChainAreSkipped() {
        lockOut(blockedIp);

        // Cliente → Cloudflare → BFF en la red de Docker: el salto interno no cuenta como cliente.
        assertThat(failedLogin(blockedIp + ", 172.18.0.5")).isEqualTo(429);
    }
}
