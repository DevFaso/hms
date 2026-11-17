package com.example.hms.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-JVM login throttle state: the fallback when Redis is not wired
 * ({@code app.redis.token-blacklist.enabled} false or unset), and the local
 * stand-in {@link RedisLoginAttemptStore} uses while Redis is unreachable.
 *
 * <p><strong>Not shared.</strong> With more than one instance, a lock set on
 * one is invisible to the others, and a reset on one leaves the lock standing
 * on the others. {@code StartupSubsystemLogger} says so at startup.
 */
@Component
@ConditionalOnProperty(
    name = "app.redis.token-blacklist.enabled",
    havingValue = "false",
    matchIfMissing = true)
public class InMemoryLoginAttemptStore implements LoginAttemptStore {

    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    @Override
    public int recordFailure(String key, int maxAttempts, long windowMs, long lockMs) {
        AttemptRecord updated = attempts.compute(key, (k, attempt) -> {
            long now = System.currentTimeMillis();
            if (attempt == null || now - attempt.windowStart > windowMs) {
                long lockedUntil = maxAttempts <= 1 ? now + lockMs : 0;
                return new AttemptRecord(now, 1, lockedUntil);
            }
            int count = attempt.failures + 1;
            long lockedUntil = count >= maxAttempts ? now + lockMs : attempt.lockedUntil;
            return new AttemptRecord(attempt.windowStart, count, lockedUntil);
        });
        return updated.failures;
    }

    @Override
    public long remainingLockMs(String key) {
        AttemptRecord attempt = attempts.get(key);
        if (attempt == null || attempt.lockedUntil == 0) {
            return 0;
        }
        long remaining = attempt.lockedUntil - System.currentTimeMillis();
        if (remaining <= 0) {
            // The lock ran out: the next failure starts a fresh window.
            attempts.remove(key, attempt);
            return 0;
        }
        return remaining;
    }

    @Override
    public void clear(String key) {
        attempts.remove(key);
    }

    @Override
    public boolean isShared() {
        return false;
    }

    private record AttemptRecord(long windowStart, int failures, long lockedUntil) {
    }
}
