package dev.alllexey.openjfs.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private MutableClock clock;

    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-24T12:00:00Z"));
        loginAttemptService = new LoginAttemptService(clock);
    }

    @Test
    void isBlocked_afterTooManyFailures() {
        fail("1.2.3.4", LoginAttemptService.MAX_FAILURES_PER_CLIENT);

        assertThat(loginAttemptService.isBlocked("1.2.3.4")).isTrue();
        assertThat(loginAttemptService.isBlocked("5.6.7.8")).isFalse();
    }

    @Test
    void isBlocked_notBeforeLimit() {
        fail("1.2.3.4", LoginAttemptService.MAX_FAILURES_PER_CLIENT - 1);

        assertThat(loginAttemptService.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void isBlocked_expiresAfterBlockDuration() {
        fail("1.2.3.4", LoginAttemptService.MAX_FAILURES_PER_CLIENT);

        clock.advance(LoginAttemptService.BLOCK_DURATION.plusSeconds(1));

        assertThat(loginAttemptService.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void recordSuccess_resetsFailures() {
        fail("1.2.3.4", LoginAttemptService.MAX_FAILURES_PER_CLIENT - 1);

        loginAttemptService.recordSuccess("1.2.3.4");
        fail("1.2.3.4", 1);

        assertThat(loginAttemptService.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void isBlocked_forEveryoneAfterTooManyFailuresFromDifferentClients() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES_TOTAL; i++) {
            loginAttemptService.recordFailure("10.0.0." + i);
        }

        assertThat(loginAttemptService.isBlocked("5.6.7.8")).isTrue();
    }

    private void fail(String client, int times) {
        for (int i = 0; i < times; i++) {
            loginAttemptService.recordFailure(client);
        }
    }

    private static class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
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
