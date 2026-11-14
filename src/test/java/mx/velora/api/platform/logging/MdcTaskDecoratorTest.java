package mx.velora.api.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcTaskDecoratorTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void theTaskSeesThePublishersContextAndTheWorkerThreadIsRestored() throws Exception {
        MDC.put("requestId", "de-quien-publica");
        var seen = new AtomicReference<String>();
        var task = new MdcTaskDecorator().decorate(() -> seen.set(MDC.get("requestId")));
        MDC.clear();

        var afterRun = new AtomicReference<String>("sin-correr");
        var worker = new Thread(() -> {
            MDC.put("requestId", "propio-del-hilo");
            task.run();
            afterRun.set(MDC.get("requestId"));
        });
        worker.start();
        worker.join();

        assertThat(seen).hasValue("de-quien-publica");
        assertThat(afterRun).hasValue("propio-del-hilo");
    }
}
