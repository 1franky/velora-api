package mx.velora.api.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation ID: cada solicitud lleva un {@code requestId} en el MDC, y por tanto en cada línea de log que produce,
 * y lo devuelve en {@code X-Request-Id}. Si el cliente (la web) manda uno válido se reutiliza para seguir la
 * solicitud de punta a punta; si no, se genera. Va primero para cubrir también la seguridad.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** Solo caracteres seguros y longitud acotada: el valor termina en logs y cabeceras. */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var requestId = accepted(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String accepted(@Nullable String candidate) {
        return candidate != null && VALID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }

    /** El {@code requestId} de la solicitud en curso, si lo hay. */
    public static @Nullable String current() {
        return MDC.get(MDC_KEY);
    }
}
