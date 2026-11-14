package mx.velora.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import mx.velora.probe.WhoAmIController;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Configuración única para pruebas de integración: todas comparten el mismo contexto de Spring y, por tanto, un solo
 * contenedor de PostGIS. No crear variantes de configuración sin necesidad: cada una arranca otro contenedor.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        // Servidor real además de MockMvc: hay comportamiento de Tomcat (p. ej. RemoteIpValve) que MockMvc no ejecuta.
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "velora.storage.originals-path=build/test-originals",
            // Las pruebas ejecutan el worker a mano para controlar el momento.
            "velora.import.worker.enabled=false"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, WhoAmIController.class})
public @interface IntegrationTest {}
