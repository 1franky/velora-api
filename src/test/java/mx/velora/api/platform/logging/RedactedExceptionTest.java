package mx.velora.api.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class RedactedExceptionTest {

    private static String printed(Throwable throwable) {
        var out = new StringWriter();
        throwable.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @Test
    void keepsTypesAndTraceButNoMessages() {
        var cause = new SQLException("valor duplicado: POINT(-99.1332 19.4326) en la rodada «Casa a la oficina»");
        var original = new IllegalStateException("token abc123 inválido", cause);

        var redacted = RedactedException.of(original);
        var text = printed(redacted);

        assertThat(text)
                .contains(IllegalStateException.class.getName(), SQLException.class.getName())
                .contains("Caused by:")
                .doesNotContain("19.4326", "Casa a la oficina", "abc123");
        assertThat(redacted.getStackTrace()).isEqualTo(original.getStackTrace());
        assertThat(redacted.getCause().getStackTrace()).isEqualTo(cause.getStackTrace());
    }

    @Test
    void survivesCircularCauses() {
        var a = new RuntimeException("a");
        var b = new RuntimeException("b", a);
        a.initCause(b);

        var redacted = RedactedException.of(b);

        assertThat(redacted.getCause()).isNotNull();
        assertThat(redacted.getCause().getCause()).isNull();
    }

    @Test
    void nullStaysNull() {
        assertThat(RedactedException.of(null)).isNull();
    }
}
