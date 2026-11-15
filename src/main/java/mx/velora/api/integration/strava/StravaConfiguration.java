package mx.velora.api.integration.strava;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Beans del conector. Las pruebas reemplazan {@link StravaAuth} con un doble {@code @Primary}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StravaProperties.class)
class StravaConfiguration {

    @Bean
    StravaAuth stravaAuth(StravaProperties properties) {
        return new HttpStravaAuth(RestClient.builder(), properties);
    }

    @Bean
    StravaApi stravaApi(StravaProperties properties, Clock clock) {
        return new HttpStravaApi(RestClient.builder(), properties, clock);
    }

    /** Ejecución periódica; solo con el conector encendido y {@code velora.strava.sync.enabled} (las pruebas no). */
    @Component
    @ConditionalOnProperty(
            name = {"velora.strava.enabled", "velora.strava.sync.enabled"},
            havingValue = "true")
    static class SyncScheduler {

        private final StravaSync sync;

        SyncScheduler(StravaSync sync) {
            this.sync = sync;
        }

        @Scheduled(fixedDelayString = "${velora.strava.sync.interval:PT15M}", initialDelayString = "PT1M")
        void run() {
            sync.syncAll();
        }
    }
}
