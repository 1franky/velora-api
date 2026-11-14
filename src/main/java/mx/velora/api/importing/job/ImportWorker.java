package mx.velora.api.importing.job;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.Locale;
import mx.velora.api.platform.logging.RedactedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    static final String JOBS_METRIC = "velora.import.jobs";
    static final String DURATION_METRIC = "velora.import.duration";
    static final String QUEUE_METRIC = "velora.import.queue";

    /** Clave del MDC con el id del trabajo en proceso: cada línea de log del intento la lleva. */
    static final String MDC_KEY = "importJobId";

    private final ImportJobRepository jobs;
    private final ImportProcessor processor;
    private final ImportProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    ImportWorker(
            ImportJobRepository jobs,
            ImportProcessor processor,
            ImportProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.jobs = jobs;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        // Se consulta solo cuando alguien lee la métrica.
        Gauge.builder(QUEUE_METRIC, jobs, repository -> repository.countByStatus("QUEUED"))
                .description("Trabajos de importación en espera")
                .tag("status", "queued")
                .register(meters);
        Gauge.builder(QUEUE_METRIC, jobs, repository -> repository.countByStatus("PROCESSING"))
                .description("Trabajos de importación en proceso")
                .tag("status", "processing")
                .register(meters);
    }

    /** Procesa la cola hasta vaciarla. Devuelve cuántos trabajos procesó. */
    public int drain() {
        var abandoned = jobs.failAbandoned(clock.instant());
        if (abandoned > 0) {
            log.error("{} importaciones agotaron sus intentos sin terminar y se marcaron como fallidas", abandoned);
            meters.counter(JOBS_METRIC, "kind", "any", "outcome", "abandoned").increment(abandoned);
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
                process(job);
                processed++;
            }
        }
    }

    /** Procesa un trabajo con su id en el MDC (cada línea de log lo lleva) y registra resultado y duración. */
    private void process(ImportJobRepository.ClaimedJob job) {
        var kind = job.isReprocess() ? "reprocess" : "import";
        var sample = Timer.start(meters);
        var outcome = "failed";
        MDC.put(MDC_KEY, job.id().toString());
        try {
            outcome = processor.process(job).name().toLowerCase(Locale.ROOT);
        } finally {
            var nanos = sample.stop(Timer.builder(DURATION_METRIC)
                    .description("Duración de cada intento de importación")
                    .tag("kind", kind)
                    .tag("outcome", outcome)
                    .register(meters));
            meters.counter(JOBS_METRIC, "kind", kind, "outcome", outcome).increment();
            log.info("Trabajo de importación ({}) terminado: {} en {} ms", kind, outcome, nanos / 1_000_000);
            MDC.remove(MDC_KEY);
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
                log.error("Fallo al revisar la cola de importación", RedactedException.of(e));
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(ImportProperties.class)
    static class ImportConfiguration {}
}
