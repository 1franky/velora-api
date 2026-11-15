package mx.velora.api.integration.strava;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Doble de {@link StravaAuth} para el contexto compartido de las pruebas: ninguna prueba sale a la red. Los códigos
 * deciden la respuesta.
 */
public class FakeStravaAuth implements StravaAuth {

    public static final long ATHLETE = 12_345L;
    public static final long OTHER_ATHLETE = 99_999L;

    final List<String> deauthorized = new CopyOnWriteArrayList<>();
    final AtomicInteger refreshes = new AtomicInteger();

    @Override
    public Tokens exchange(String code) {
        return switch (code) {
            case "rechazado" -> throw new RejectedException();
            case "caido" -> throw new UnavailableException(new IllegalStateException("prueba"));
            case "otro-atleta" -> tokens("acceso-otro", OTHER_ATHLETE);
            default -> tokens("acceso-" + code, ATHLETE);
        };
    }

    @Override
    public Tokens refresh(String refreshToken) {
        var n = refreshes.incrementAndGet();
        return new Tokens(
                "acceso-renovado-" + n, "refresh-renovado-" + n, Instant.now().plusSeconds(21_600), null);
    }

    @Override
    public void deauthorize(String accessToken) {
        deauthorized.add(accessToken);
    }

    private static Tokens tokens(String access, long athlete) {
        return new Tokens(access, "refresh-" + access, Instant.now().plusSeconds(21_600), athlete);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        FakeStravaAuth fakeStravaAuth() {
            return new FakeStravaAuth();
        }
    }
}
