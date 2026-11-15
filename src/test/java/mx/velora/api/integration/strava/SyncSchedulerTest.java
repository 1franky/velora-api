package mx.velora.api.integration.strava;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * La sincronización periódica se programa con solo encender el conector. Antes exigía también
 * {@code velora.strava.sync.enabled=true} explícito, que producción no define, y nunca corría.
 */
class SyncSchedulerTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final StravaSync sync = mock(StravaSync.class);

    private ApplicationContextRunner runner(boolean enabled, boolean syncEnabled) {
        var properties = new StravaProperties(
                enabled,
                "id",
                "secreto",
                KEY,
                URI.create("https://velora.local/retorno"),
                URI.create("https://www.strava.com"),
                Duration.ofMinutes(10),
                null,
                new StravaProperties.Sync(syncEnabled, Duration.ofMinutes(15), 40),
                new StravaProperties.Webhook(false, null));
        return new ApplicationContextRunner()
                .withPropertyValues("velora.strava.enabled=" + enabled)
                .withBean(StravaSync.class, () -> sync)
                .withBean(StravaProperties.class, () -> properties)
                .withUserConfiguration(StravaConfiguration.SyncScheduler.class);
    }

    @Test
    void runsWithTheConnectorOnAndNoExplicitSyncProperty() {
        runner(true, true).run(context -> {
            assertThat(context).hasSingleBean(StravaConfiguration.SyncScheduler.class);
            context.getBean(StravaConfiguration.SyncScheduler.class).run();
            verify(sync).syncAll();
        });
    }

    @Test
    void syncDisabledSkipsTheRun() {
        runner(true, false).run(context -> {
            context.getBean(StravaConfiguration.SyncScheduler.class).run();
            verify(sync, never()).syncAll();
        });
    }

    @Test
    void connectorOffSchedulesNothing() {
        runner(false, true)
                .run(context -> assertThat(context).doesNotHaveBean(StravaConfiguration.SyncScheduler.class));
    }
}
