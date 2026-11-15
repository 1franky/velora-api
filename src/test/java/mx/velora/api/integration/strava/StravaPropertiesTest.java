package mx.velora.api.integration.strava;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StravaPropertiesTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final URI STRAVA = URI.create("https://www.strava.com");
    private static final URI RETURN = URI.create("https://velora.local/retorno");

    @Test
    void disabledNeedsNothing() {
        assertThatCode(() -> new StravaProperties(false, null, null, null, null, STRAVA, Duration.ofMinutes(10)))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledRequiresCredentialsKeyAndReturnUrl() {
        assertThatThrownBy(() -> new StravaProperties(true, "id", null, KEY, RETURN, STRAVA, Duration.ofMinutes(10)))
                .hasMessageContaining("client-secret");
        assertThatThrownBy(() -> new StravaProperties(true, "id", "s", KEY, null, STRAVA, Duration.ofMinutes(10)))
                .hasMessageContaining("redirect-uri");
        assertThatThrownBy(
                        () -> new StravaProperties(true, "id", "s", "Y29ydGE=", RETURN, STRAVA, Duration.ofMinutes(10)))
                .hasMessageContaining("32 bytes");
        assertThatCode(() -> new StravaProperties(true, "id", "s", KEY, RETURN, STRAVA, Duration.ofMinutes(10)))
                .doesNotThrowAnyException();
    }
}
