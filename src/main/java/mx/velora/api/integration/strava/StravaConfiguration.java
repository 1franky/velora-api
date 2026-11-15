package mx.velora.api.integration.strava;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
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

    @Bean
    StravaSubscriptions stravaSubscriptions(StravaProperties properties) {
        return new HttpStravaSubscriptions(RestClient.builder(), properties);
    }

    @Bean
    StravaWebhookRegistrar stravaWebhookRegistrar(
            StravaProperties properties, StravaSubscriptions subscriptions, StravaWebhooks webhooks) {
        return new StravaWebhookRegistrar(properties, subscriptions, webhooks);
    }

    /** Webhooks: registra la suscripción con la API ya sirviendo y procesa los eventos guardados cada 30 s. */
    @Component
    @ConditionalOnProperty(name = "velora.strava.enabled", havingValue = "true")
    static class WebhookScheduler {

        private final StravaWebhooks webhooks;
        private final StravaWebhookRegistrar registrar;
        private final StravaProperties properties;

        WebhookScheduler(StravaWebhooks webhooks, StravaWebhookRegistrar registrar, StravaProperties properties) {
            this.webhooks = webhooks;
            this.registrar = registrar;
            this.properties = properties;
        }

        @EventListener(ApplicationReadyEvent.class)
        void register() {
            if (properties.webhook().enabled()) {
                // En otro hilo: Strava valida el callback llamando a esta misma API.
                Thread.ofVirtual().name("strava-webhook-registro").start(registrar::register);
            }
        }

        @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
        void process() {
            if (properties.webhook().enabled()) {
                webhooks.processPending();
            }
        }
    }

    /**
     * Ejecución periódica con el conector encendido; {@code velora.strava.sync.enabled=false} la apaga (las pruebas).
     * Se revisa en el método y no en la condición: {@code @ConditionalOnProperty} no ve el {@code true} por omisión
     * de {@link StravaProperties.Sync}, y sin la propiedad explícita el bean no se creaba.
     */
    @Component
    @ConditionalOnProperty(name = "velora.strava.enabled", havingValue = "true")
    static class SyncScheduler {

        private final StravaSync sync;
        private final StravaProperties properties;

        SyncScheduler(StravaSync sync, StravaProperties properties) {
            this.sync = sync;
            this.properties = properties;
        }

        @Scheduled(fixedDelayString = "${velora.strava.sync.interval:PT15M}", initialDelayString = "PT1M")
        void run() {
            if (properties.sync().enabled()) {
                sync.syncAll();
            }
        }
    }
}
