package mx.velora.api.integration.strava;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StravaWebhookRegistrarTest {

    private static final URI CALLBACK = URI.create("https://api.velora.test/api/v1/integrations/strava/webhook");

    private final StravaSubscriptions subscriptions = mock(StravaSubscriptions.class);
    private final StravaWebhooks webhooks = mock(StravaWebhooks.class);
    private StravaWebhookRegistrar registrar;

    @BeforeEach
    void setUp() {
        when(webhooks.verifyToken()).thenReturn("token-de-verificacion");
        registrar = registrar(CALLBACK);
    }

    private StravaWebhookRegistrar registrar(URI callback) {
        return new StravaWebhookRegistrar(
                new StravaProperties(
                        true,
                        "1",
                        "s",
                        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                        URI.create("https://velora.test/retorno"),
                        URI.create("https://strava.test"),
                        Duration.ofMinutes(10),
                        null,
                        new StravaProperties.Sync(true, Duration.ofMinutes(15), 40),
                        new StravaProperties.Webhook(true, callback)),
                subscriptions,
                webhooks);
    }

    @Test
    void reusesASubscriptionToTheSameCallback() {
        when(subscriptions.list()).thenReturn(List.of(new StravaSubscriptions.Subscription(5, CALLBACK)));

        registrar.register();

        verify(webhooks).useSubscription(5);
        verify(subscriptions, never()).create(any(), any());
    }

    @Test
    void replacesASubscriptionToAnotherCallback() {
        when(subscriptions.list())
                .thenReturn(List.of(new StravaSubscriptions.Subscription(5, URI.create("https://vieja.test/hook"))));
        when(subscriptions.create(CALLBACK, "token-de-verificacion")).thenReturn(6L);

        registrar.register();

        verify(subscriptions).delete(5);
        verify(webhooks).useSubscription(6);
    }

    @Test
    void createsOneWhenMissingAndSurvivesStravaFailures() {
        when(subscriptions.list()).thenReturn(List.of());
        when(subscriptions.create(CALLBACK, "token-de-verificacion")).thenReturn(7L);
        registrar.register();
        verify(webhooks).useSubscription(7);

        when(subscriptions.list()).thenThrow(new IllegalStateException("Strava caído"));
        registrar.register();
        assertThat(true).as("no propaga el fallo").isTrue();
    }
}
