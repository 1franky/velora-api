package mx.velora.api.importing.job;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker asíncrono de importación dentro de la API (ADR-001: sin servicios separados hasta una necesidad medida).
 * Varias instancias pueden convivir: la cola usa {@code FOR UPDATE SKIP LOCKED}.
 */
@Component
public class ImportWorker {

    private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);

    private final ImportJobRepository jobs;
    private final ImportProcessor processor;
    private final ImportProperties properties;
    private final Clock clock;

    ImportWorker(ImportJobRepository jobs, ImportProcessor processor, ImportProperties properties, Clock clock) {
        this.jobs = jobs;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    /** Procesa la cola hasta vaciarla. Devuelve cuántos trabajos procesó. */
    public int drain() {
        var abandoned = jobs.failAbandoned(clock.instant());
        if (abandoned > 0) {
            log.error("{} importaciones agotaron sus intentos sin terminar y se marcaron como fallidas", abandoned);
        }
        var processed = 0;
        while (true) {
            var batch = jobs.claim(
                    clock.instant(),
                    properties.worker().lockDuration(),
                    properties.worker().batchSize());
            if (batch.isEmpty()) {
                return processed;
            }
            for (var job : batch) {
                processor.process(job);
                processed++;
            }
        }
    }

    /** Ejecución periódica; deshabilitable con {@code velora.import.worker.enabled=false} (pruebas). */
    @Component
    @ConditionalOnProperty(name = "velora.import.worker.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduler {

        private final ImportWorker worker;

        Scheduler(ImportWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${velora.import.worker.poll-interval:PT2S}")
        void poll() {
            try {
                worker.drain();
            } catch (RuntimeException e) {
                // La base puede no estar disponible un momento; el siguiente ciclo lo reintenta.
                log.error("Fallo al revisar la cola de importación", e);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(ImportProperties.class)
    static class ImportConfiguration {}
}
