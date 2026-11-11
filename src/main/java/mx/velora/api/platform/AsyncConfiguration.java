package mx.velora.api.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Habilita {@code @Async}: los listeners entre módulos ({@code @ApplicationModuleListener}) corren fuera de la
 * solicitud que publicó el evento, con el ejecutor de tareas que configura Spring Boot.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfiguration {}
