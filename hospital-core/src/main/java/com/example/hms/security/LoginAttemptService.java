package com.example.hms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks failed login attempts per username with a sliding window.
 * Locks the account for {@value #LOCK_DURATION_MS} ms after {@value #MAX_ATTEMPTS} failures
 * within the window.
 *
 * <p><strong>Limitation:</strong> In-memory; lost on restart and not shared across instances.
 * Replace with Redis when horizontal scaling is required.</p>
 */
@Slf4j
@Service
public class LoginAttemptService {

    static final int MAX_ATTEMPTS = 5;
    static final long LOCK_DURATION_MS = 15 * 60 * 1000L; // 15 minutes
    private static final long WINDOW_MS = LOCK_DURATION_MS;

    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    /**
     * Record a failed login attempt.
     */
    public void recordFailure(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String key = username.toLowerCase();
        attempts.compute(key, (k, attempt) -> {
            long now = System.currentTimeMillis();
            if (attempt == null || now - attempt.windowStart > WINDOW_MS) {
                return new AttemptRecord(now, 1, 0);
            }
            int newCount = attempt.failures + 1;
            long lockUntil = newCount >= MAX_ATTEMPTS ? now + LOCK_DURATION_MS : attempt.lockedUntil;
            if (newCount >= MAX_ATTEMPTS) {
                log.warn("[LOGIN-THROTTLE] Account '{}' locked after {} failed attempts", username, newCount);
            }
            return new AttemptRecord(attempt.windowStart, newCount, lockUntil);
        });
    }

    /**
     * Move any throttle state from one username key to another.
     *
     * <p>The counter is keyed on the username, so renaming an account
     * otherwise voids a live lockout — nothing would ever look the old key up
     * again, and it would sit in the map until eviction while the account
     * walked free under its new name. That would make a rename a
     * lockout-clearing primitive on an endpoint that is not role-gated.
     *
     * <p>No-op when the names match, case-insensitively. Otherwise the
     * destination is always overwritten — with the renamed account's own
     * record, or with nothing when it had none. Whatever sat under the new
     * name belonged to a different account and must not be inherited:
     * failures are recorded for unknown usernames too, so an unused name can
     * already be locked, and renaming into it would lock the renamed account
     * out for up to the lock duration.
     *
     * <p>The move is a remove followed by a put, so a failure recorded against
     * either key in between would be stranded or clobbered. The sweep after
     * the put folds any such record in, taking whichever of the two is closer
     * to a lockout. That closes the window on this instance; across instances
     * the counter is not shared at all (see the standing-debt note).
     */
    public void renameKey(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) {
            return;
        }
        String oldKey = from.toLowerCase();
        String newKey = to.toLowerCase();
        if (oldKey.equals(newKey)) {
            return;
        }
        AttemptRecord carried = attempts.remove(oldKey);
        if (carried == null) {
            attempts.remove(newKey);
        } else {
            attempts.put(newKey, carried);
            log.info("[LOGIN-THROTTLE] Carried throttle state across a rename");
        }
        // Anything recorded against the old key while the swap was in flight
        // would otherwise sit under a key nobody reads again — which is the
        // lockout-voiding this method exists to prevent, just in a narrower
        // window.
        AttemptRecord stranded = attempts.remove(oldKey);
        if (stranded != null) {
            attempts.merge(newKey, stranded, LoginAttemptService::closerToLockout);
        }
    }

    /** Of two records for the same account, the one nearer a lockout wins. */
    private static AttemptRecord closerToLockout(AttemptRecord a, AttemptRecord b) {
        if (a.lockedUntil != b.lockedUntil) {
            return a.lockedUntil > b.lockedUntil ? a : b;
        }
        return a.failures >= b.failures ? a : b;
    }

    /**
     * Check whether the account is currently locked.
     */
    public boolean isLocked(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        AttemptRecord attempt = attempts.get(username.toLowerCase());
        if (attempt == null) {
            return false;
        }
        if (attempt.lockedUntil > 0 && System.currentTimeMillis() < attempt.lockedUntil) {
            return true;
        }
        // Lock expired — clean up
        if (attempt.lockedUntil > 0) {
            attempts.remove(username.toLowerCase());
        }
        return false;
    }

    /**
     * Returns remaining lock time in milliseconds, or 0 if not locked.
     */
    public long remainingLockMs(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        AttemptRecord attempt = attempts.get(username.toLowerCase());
        if (attempt == null || attempt.lockedUntil == 0) {
            return 0;
        }
        long remaining = attempt.lockedUntil - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    /**
     * Reset attempts after a successful login.
     */
    public void resetAttempts(String username) {
        if (username != null) {
            attempts.remove(username.toLowerCase());
        }
    }

    private record AttemptRecord(long windowStart, int failures, long lockedUntil) {}
}
