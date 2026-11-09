package mx.velora.api.importing.parsing;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/** Acceso a fixtures sintéticos de {@code src/test/resources/fixtures}. */
final class Fixtures {

    private Fixtures() {}

    static InputStream open(String path) {
        return Objects.requireNonNull(
                Fixtures.class.getResourceAsStream("/fixtures/" + path), () -> "Fixture inexistente: " + path);
    }

    static byte[] bytes(String path) {
        try (var in = open(path)) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
