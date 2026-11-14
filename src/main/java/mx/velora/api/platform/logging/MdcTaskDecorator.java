package mx.velora.api.platform.logging;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Copia el MDC del hilo que encola la tarea al hilo que la ejecuta: los listeners asíncronos de eventos conservan el
 * {@code requestId} (o el {@code importJobId}) de quien publicó el evento.
 */
public final class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        var captured = MDC.getCopyOfContextMap();
        return () -> {
            var previous = MDC.getCopyOfContextMap();
            replace(captured);
            try {
                task.run();
            } finally {
                replace(previous);
            }
        };
    }

    private static void replace(@Nullable Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
