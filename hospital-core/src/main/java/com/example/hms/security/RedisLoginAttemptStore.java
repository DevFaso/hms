package com.example.hms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Login throttle state in Redis, shared by every instance: a lock set on one
 * instance refuses the login on all of them, and a reset on one (a successful
 * login, any activation path) clears it for all of them. Active when
 * {@code app.redis.token-blacklist.enabled=true}, the flag that says Redis is
 * wired, as for {@link RedisTokenBlacklistService}.
 *
 * <p>Key schema, mirroring the blacklist and idle-session keys:
 * {@code hms:login:fail:<key>} holds the failure count and expires one window
 * after the first failure; {@code hms:login:lock:<key>} exists while the key is
 * locked and expires with the lock. The count, its expiry and the lock are
 * written by one Lua script, so two instances counting at once cannot lose a
 * failure or leave a counter without an expiry. Lock time is read as the
 * key's TTL, on Redis's clock: instance clocks never disagree about it.
 *
 * <p>When Redis is unreachable the store degrades to a local in-memory one
 * rather than to no throttle at all: brute force stays bounded per instance
 * during the outage. A throttled WARN says so. State recorded locally is not
 * carried back into Redis when it returns; clears go to both.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.redis.token-blacklist.enabled", havingValue = "true")
public class RedisLoginAttemptStore implements LoginAttemptStore {

    static final String FAIL_PREFIX = "hms:login:fail:";
    static final String LOCK_PREFIX = "hms:login:lock:";
    private static final long OUTAGE_LOG_THROTTLE_MS = 60_000L;

    /**
     * KEYS[1] the counter, KEYS[2] the lock; ARGV window ms, max attempts,
     * lock ms. Returns the count.
     */
    private static final RedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>(
        "local n = redis.call('INCR', KEYS[1]) "
            + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
            + "if n >= tonumber(ARGV[2]) then redis.call('SET', KEYS[2], '1', 'PX', ARGV[3]) end "
            + "return n",
        Long.class);

    private final StringRedisTemplate redis;
    private final InMemoryLoginAttemptStore fallback = new InMemoryLoginAttemptStore();
    private final AtomicLong lastOutageLogMs = new AtomicLong(0L);

    public RedisLoginAttemptStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public int recordFailure(String key, int maxAttempts, long windowMs, long lockMs) {
        try {
            Long count = redis.execute(RECORD_FAILURE, List.of(FAIL_PREFIX + key, LOCK_PREFIX + key),
                Long.toString(windowMs), Integer.toString(maxAttempts), Long.toString(lockMs));
            return count == null ? 0 : count.intValue();
        } catch (DataAccessException ex) {
            outage("recordFailure", ex);
            return fallback.recordFailure(key, maxAttempts, windowMs, lockMs);
        }
    }

    @Override
    public long remainingLockMs(String key) {
        try {
            Long ttl = redis.getExpire(LOCK_PREFIX + key, java.util.concurrent.TimeUnit.MILLISECONDS);
            // -2: no lock; -1: a lock without expiry, which this store never writes.
            return ttl == null || ttl <= 0 ? 0 : ttl;
        } catch (DataAccessException ex) {
            outage("remainingLockMs", ex);
            return fallback.remainingLockMs(key);
        }
    }

    @Override
    public void clear(String key) {
        fallback.clear(key);
        try {
            redis.delete(List.of(FAIL_PREFIX + key, LOCK_PREFIX + key));
        } catch (DataAccessException ex) {
            outage("clear", ex);
        }
    }

    @Override
    public boolean isShared() {
        return true;
    }

    /** At most one WARN a minute per instance, so an outage cannot flood the log. */
    private void outage(String operation, DataAccessException ex) {
        long now = System.currentTimeMillis();
        long previous = lastOutageLogMs.get();
        if (now - previous >= OUTAGE_LOG_THROTTLE_MS && lastOutageLogMs.compareAndSet(previous, now)) {
            log.warn("[LOGIN-THROTTLE] Redis unavailable on {} ({}); counting on this instance only until it returns",
                operation, ex.getClass().getSimpleName());
        }
    }
}
