package mx.velora.api.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private static final String EMAIL = "email:propietario@example.com";
    private static final String IP = "ip:10.0.0.1";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T18:00:00Z"));
    private final LoginThrottle throttle = new LoginThrottle(
            new AuthProperties(
                    Duration.ofMinutes(15),
                    Duration.ofDays(30),
                    new AuthProperties.Throttle(
                            5, Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(15))),
            clock);

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            throttle.recordFailure(EMAIL, IP);
        }
    }

    @Test
    void allowsFirstFourFailuresWithoutWaiting() {
        fail(4);

        assertThat(throttle.retryAfter(EMAIL, IP)).isEmpty();
    }

    @Test
    void locksAfterFifthFailureAndDoublesEachTime() {
        fail(5);
        assertThat(throttle.retryAfter(EMAIL)).contains(Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(31));
        assertThat(throttle.retryAfter(EMAIL)).isEmpty();

        fail(1);
        assertThat(throttle.retryAfter(EMAIL)).contains(Duration.ofSeconds(60));
    }

    @Test
    void capsWaitAtMaximum() {
        fail(30);

        assertThat(throttle.retryAfter(IP)).contains(Duration.ofMinutes(15));
    }

    @Test
    void lockOnAnyKeyBlocksTheAttempt() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure(IP);
        }

        assertThat(throttle.retryAfter(EMAIL, IP)).isPresent();
        assertThat(throttle.retryAfter(EMAIL)).isEmpty();
    }

    @Test
    void successForgetsFailures() {
        fail(4);
        throttle.recordSuccess(EMAIL, IP);
        fail(4);

        assertThat(throttle.retryAfter(EMAIL, IP)).isEmpty();
    }

    @Test
    void failuresOutsideTheWindowAreForgotten() {
        fail(4);
        clock.advance(Duration.ofMinutes(16));
        fail(1);

        assertThat(throttle.retryAfter(EMAIL, IP)).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
