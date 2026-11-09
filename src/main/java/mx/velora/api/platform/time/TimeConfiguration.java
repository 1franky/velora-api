package mx.velora.api.platform.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj único de la aplicación, en UTC. Las pruebas pueden sustituirlo para controlar el tiempo. */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
