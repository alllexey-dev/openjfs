package dev.alllexey.openjfs.security;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// slows down password guessing: too many failed logins block further attempts for a while
@Service
public class LoginAttemptService {

    static final int MAX_FAILURES_PER_CLIENT = 5;

    // the client address comes from X-Forwarded-For and can be spoofed, so there is a global limit too
    static final int MAX_FAILURES_TOTAL = 30;

    static final Duration BLOCK_DURATION = Duration.ofMinutes(15);

    private static final String TOTAL_KEY = "*";

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Clock clock;

    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String client) {
        return reachedLimit(client, MAX_FAILURES_PER_CLIENT) || reachedLimit(TOTAL_KEY, MAX_FAILURES_TOTAL);
    }

    public void recordFailure(String client) {
        Instant now = clock.instant();
        increment(client, now);
        increment(TOTAL_KEY, now);
        if (failures.size() > CLEANUP_THRESHOLD) {
            failures.values().removeIf(entry -> entry.isExpired(now));
        }
    }

    public void recordSuccess(String client) {
        failures.remove(client);
    }

    private boolean reachedLimit(String key, int limit) {
        Failures entry = failures.get(key);
        return entry != null && !entry.isExpired(clock.instant()) && entry.count() >= limit;
    }

    private void increment(String key, Instant now) {
        failures.compute(key, (k, entry) -> entry == null || entry.isExpired(now)
                ? new Failures(1, now)
                : new Failures(entry.count() + 1, entry.firstFailure()));
    }

    private record Failures(int count, Instant firstFailure) {

        boolean isExpired(Instant now) {
            return now.isAfter(firstFailure.plus(BLOCK_DURATION));
        }
    }
}
