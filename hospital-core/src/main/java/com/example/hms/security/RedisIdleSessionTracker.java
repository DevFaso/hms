package com.example.hms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed {@link IdleSessionTracker}. Replaces
 * {@link InMemoryIdleSessionTracker} when
 * {@code app.auth.idle-tracking.enabled=true}.
 *
 * <p>Key schema: {@code hms:idle:user:<uuid>} → epoch-millis of last touch
 * (string). TTL is the configured idle window so eviction is automatic and
 * no scheduled sweeper is needed. The schema deliberately mirrors
 * {@link RedisTokenBlacklistService} so operators have a single mental
 * model for HMS Redis usage.
 *
 * <p>Fail-open semantics: when Redis is unavailable
 * ({@link DataAccessException}) the tracker emits a throttled WARN and
 * treats the call as successful (touch becomes a no-op, isIdle returns
 * false). A lingering Redis outage must NOT lock every clinician out of
 * the system — security risk vs. availability risk, and clinical
 * availability wins for this gate. The throttle uses a per-instance
 * "log at most once per minute" gate so an outage doesn't flood logs.
 *
 * <p>Added in v1.0 / Security / Idle session timeout (roadmap row 7).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.auth.idle-tracking.enabled", havingValue = "true")
public class RedisIdleSessionTracker implements IdleSessionTracker {

    private static final String KEY_PREFIX = "hms:idle:user:";
    private static final long FAIL_OPEN_LOG_THROTTLE_MS = 60_000L;

    private final StringRedisTemplate redisTemplate;
    private final Duration idleWindow;
    private final boolean failOpen;
    private final AtomicLong lastFailOpenLogMs = new AtomicLong(0L);

    public RedisIdleSessionTracker(
        StringRedisTemplate redisTemplate,
        @Value("${app.auth.idle-window:PT15M}") Duration idleWindow,
        @Value("${app.auth.idle-tracking.fail-open:true}") boolean failOpen
    ) {
        this.redisTemplate = redisTemplate;
        this.idleWindow = idleWindow;
        this.failOpen = failOpen;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void touch(UUID userId) {
        if (userId == null) return;
        try {
            redisTemplate.opsForValue().set(
                KEY_PREFIX + userId,
                Long.toString(System.currentTimeMillis()),
                idleWindow
            );
        } catch (DataAccessException ex) {
            handleRedisOutage("touch", userId, ex);
        }
    }

    @Override
    public boolean isIdle(UUID userId) {
        if (userId == null) return false;
        try {
            return !Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + userId));
        } catch (DataAccessException ex) {
            handleRedisOutage("isIdle", userId, ex);
            // Fail-open returns false (treat as not-idle) so clinicians
            // do not get logged out by an unrelated Redis blip.
            return false;
        }
    }

    @Override
    public void clear(UUID userId) {
        if (userId == null) return;
        try {
            redisTemplate.delete(KEY_PREFIX + userId);
        } catch (DataAccessException ex) {
            handleRedisOutage("clear", userId, ex);
        }
    }

    /**
     * Throttled WARN so a Redis outage doesn't flood logs. The userId is
     * an opaque UUID — no PHI, safe to log. The fail-open flag is
     * surfaced so the operator sees the policy in effect, not just the
     * exception cause.
     */
    private void handleRedisOutage(String op, UUID userId, DataAccessException ex) {
        long now = System.currentTimeMillis();
        long previous = lastFailOpenLogMs.get();
        if (now - previous >= FAIL_OPEN_LOG_THROTTLE_MS
            && lastFailOpenLogMs.compareAndSet(previous, now)) {
            log.warn(
                "[IDLE-TRACKER] Redis unavailable on {} for user={} (failOpen={}): {}",
                op, userId, failOpen, ex.getMessage()
            );
        }
        // failOpen=false would re-throw, but we've defaulted to true and
        // the operator can flip the property if they prefer fail-closed.
        // For now we honour the policy by returning normally — callers
        // (touch / clear) are void; isIdle returns false above.
        if (!failOpen) {
            throw ex;
        }
    }
}
