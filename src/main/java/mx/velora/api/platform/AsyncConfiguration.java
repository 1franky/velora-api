package mx.velora.api.platform;

import mx.velora.api.platform.logging.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Habilita {@code @Async}: los listeners entre módulos ({@code @ApplicationModuleListener}) corren fuera de la
 * solicitud que publicó el evento, con el ejecutor de tareas que configura Spring Boot. Ese ejecutor aplica el
 * {@link TaskDecorator}, que les pasa el MDC (correlation ID) de quien publicó.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfiguration {

    @Bean
    TaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }
}
