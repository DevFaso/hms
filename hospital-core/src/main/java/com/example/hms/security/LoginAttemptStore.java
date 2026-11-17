package com.example.hms.security;

/**
 * Where the login throttle's counters live. {@link LoginAttemptService} owns
 * the policy (what the key is, how many failures, how long a lock); a store
 * only keeps state for an opaque key.
 *
 * <p>Two implementations, chosen by the flag that says Redis is wired
 * ({@code app.redis.token-blacklist.enabled}), the same switch as the token
 * blacklist: {@link RedisLoginAttemptStore}, shared by every instance, and
 * {@link InMemoryLoginAttemptStore}, per JVM, for single-instance dev and the
 * tests. The startup log says which one is live.
 */
public interface LoginAttemptStore {

    /**
     * Count one failure. The count lives {@code windowMs} from the first
     * failure of its window; reaching {@code maxAttempts} locks the key for
     * {@code lockMs} from this failure.
     *
     * @return the failure count in the current window, this one included
     */
    int recordFailure(String key, int maxAttempts, long windowMs, long lockMs);

    /** Milliseconds left on the key's lock, 0 when it is not locked. */
    long remainingLockMs(String key);

    /** Forget the key's failures and lock. */
    void clear(String key);

    /** True when every instance sees the same counters. */
    boolean isShared();
}
