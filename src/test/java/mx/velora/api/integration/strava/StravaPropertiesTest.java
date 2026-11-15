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
    private static final StravaProperties.Sync SYNC = new StravaProperties.Sync(true, Duration.ofMinutes(15), 40);

    @Test
    void disabledNeedsNothing() {
        assertThatCode(() ->
                        new StravaProperties(false, null, null, null, null, STRAVA, Duration.ofMinutes(10), null, SYNC))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledRequiresCredentialsKeyAndReturnUrl() {
        assertThatThrownBy(() ->
                        new StravaProperties(true, "id", null, KEY, RETURN, STRAVA, Duration.ofMinutes(10), null, SYNC))
                .hasMessageContaining("client-secret");
        assertThatThrownBy(() ->
                        new StravaProperties(true, "id", "s", KEY, null, STRAVA, Duration.ofMinutes(10), null, SYNC))
                .hasMessageContaining("redirect-uri");
        assertThatThrownBy(() -> new StravaProperties(
                        true, "id", "s", "Y29ydGE=", RETURN, STRAVA, Duration.ofMinutes(10), null, SYNC))
                .hasMessageContaining("32 bytes");
        assertThatCode(() ->
                        new StravaProperties(true, "id", "s", KEY, RETURN, STRAVA, Duration.ofMinutes(10), null, SYNC))
                .doesNotThrowAnyException();
    }

    @Test
    void anEmptyBackfillSinceFromTheEnvironmentMeansTheWholeHistory() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                java.util.Map.of("velora.strava.backfill-since", "", "velora.strava.enabled", "false"));
        var bound = new org.springframework.boot.context.properties.bind.Binder(source)
                .bindOrCreate("velora.strava", StravaProperties.class);
        org.assertj.core.api.Assertions.assertThat(bound.backfillSince()).isNull();

        var dated = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                java.util.Map.of("velora.strava.backfill-since", "2024-01-01"));
        org.assertj.core.api.Assertions.assertThat(new org.springframework.boot.context.properties.bind.Binder(dated)
                        .bindOrCreate("velora.strava", StravaProperties.class)
                        .backfillSince())
                .isEqualTo(java.time.LocalDate.of(2024, 1, 1));
    }
}
