package mx.velora.api.platform.logging;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Copia de una excepción sin sus mensajes, para registrarla. El mensaje de una excepción puede traer datos que no deben
 * llegar a los logs: un error de PostgreSQL cita los valores de la fila (coordenadas, títulos), un parser cita el
 * contenido del archivo, un cliente HTTP la URL con parámetros. Se conservan el tipo original, la traza y la cadena de
 * causas, que es lo que sirve para diagnosticar; el detalle se reproduce con el {@code errorId} o el id del trabajo.
 *
 * <pre>{@code log.error("Importación {} falló", jobId, RedactedException.of(e));}</pre>
 */
public final class RedactedException extends RuntimeException {

    private static final int MAX_CAUSES = 16;

    private RedactedException(Throwable original, Throwable cause) {
        // Solo el nombre de la clase original: SLF4J imprime "RedactedException: java.sql.SQLException" y la traza.
        super(original.getClass().getName(), cause, false, true);
        setStackTrace(original.getStackTrace());
    }

    /** La excepción sin mensajes, con su traza y sus causas (también sin mensajes); null si recibe null. */
    public static Throwable of(Throwable throwable) {
        return copy(throwable, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static Throwable copy(Throwable throwable, Set<Throwable> seen, int depth) {
        // Una causa circular o una cadena enorme no deben colgar el registro.
        if (throwable == null || depth >= MAX_CAUSES || !seen.add(throwable)) {
            return null;
        }
        return new RedactedException(throwable, copy(throwable.getCause(), seen, depth + 1));
    }
}
