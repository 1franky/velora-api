package mx.velora.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/** Actuator vive en un puerto que no se publica; en el puerto público solo queda la salud. */
@IntegrationTest
class ManagementPortIntegrationTest {

    @LocalServerPort
    int publicPort;

    @LocalManagementPort
    int managementPort;

    private RestClient http;

    @BeforeEach
    void client() {
        http = RestClient.builder()
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    private int status(int port, String path) {
        return http.get()
                .uri("http://localhost:" + port + path)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value();
    }

    private String body(int port, String path) {
        return http.get().uri("http://localhost:" + port + path).retrieve().body(String.class);
    }

    @Test
    void publicPortOnlyServesHealth() {
        assertThat(managementPort).isNotEqualTo(publicPort);
        assertThat(body(publicPort, "/healthz")).contains("\"status\":\"UP\"");
        assertThat(status(publicPort, "/actuator/health")).isEqualTo(404);
        assertThat(status(publicPort, "/actuator/metrics")).isEqualTo(404);
        assertThat(status(publicPort, "/actuator/health/readiness")).isEqualTo(404);
    }

    @Test
    void managementPortServesProbesAndMetricsWithoutSession() {
        assertThat(status(managementPort, "/actuator/health/readiness")).isEqualTo(200);
        assertThat(status(managementPort, "/actuator/health/liveness")).isEqualTo(200);
        assertThat(body(managementPort, "/actuator/metrics")).contains("\"names\"");
    }
}
