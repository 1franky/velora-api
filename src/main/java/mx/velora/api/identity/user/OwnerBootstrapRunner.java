package mx.velora.api.identity.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Comando de una sola vez, activo solo con {@code --velora.bootstrap-owner} (tarea {@code ./gradlew bootstrapOwner}).
 * Lee {@code VELORA_OWNER_EMAIL} y {@code VELORA_OWNER_PASSWORD}, crea el propietario y termina el proceso. Si algo
 * falla, la aplicación no arranca y el proceso termina con error.
 */
@Component
@ConditionalOnProperty("velora.bootstrap-owner")
class OwnerBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrapRunner.class);

    private final OwnerBootstrap bootstrap;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    OwnerBootstrapRunner(OwnerBootstrap bootstrap, Environment environment, ConfigurableApplicationContext context) {
        this.bootstrap = bootstrap;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        var id = bootstrap.createOwner(
                environment.getProperty("velora.owner.email"), environment.getProperty("velora.owner.password"));
        log.info("Usuario propietario creado (id={})", id);
        // Es un comando, no un servicio: cerrar el contexto y salir aunque queden hilos no daemon.
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
