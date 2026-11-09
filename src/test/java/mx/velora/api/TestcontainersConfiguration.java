package mx.velora.api;

import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Misma imagen que `compose.yaml`; mantenerlas sincronizadas. */
    static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:18-3.7").asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        // La imagen de PostGIS inicializa extensiones y reinicia el servidor: tarda más que `postgres`.
        return new PostgreSQLContainer(POSTGIS_IMAGE).withStartupTimeout(Duration.ofMinutes(3));
    }
}
