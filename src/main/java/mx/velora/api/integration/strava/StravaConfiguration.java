package mx.velora.api.integration.strava;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Beans del conector. Las pruebas reemplazan {@link StravaAuth} con un doble {@code @Primary}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StravaProperties.class)
class StravaConfiguration {

    @Bean
    StravaAuth stravaAuth(StravaProperties properties) {
        return new HttpStravaAuth(RestClient.builder(), properties);
    }
}
