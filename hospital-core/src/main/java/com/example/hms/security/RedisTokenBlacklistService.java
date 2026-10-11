package com.example.hms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed token blacklist. Replaces {@link InMemoryTokenBlacklistService}
 * when {@code app.redis.token-blacklist.enabled=true}.
 *
 * <p>Entries use Redis TTL so no scheduled eviction is required. Blacklist
 * state is shared across application instances, which is mandatory for
 * horizontal scaling.
 *
 * <p>Key schema: {@code hms:blacklist:jti:<jti>} → expiration epoch-millis
 * (as a string). TTL is derived from the supplied expiration timestamp.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.redis.token-blacklist.enabled", havingValue = "true")
public class RedisTokenBlacklistService implements TokenBlacklistService {

    private static final String KEY_PREFIX = "hms:blacklist:jti:";
    private static final String ROTATED_PREFIX = "hms:rotated:jti:";
    /** A claim's value carries this prefix, a revocation's does not: release removes only the former. */
    private static final String CLAIM_VALUE_PREFIX = "claim:";
    /** Delete the key only if it still holds the claim's own value (atomic). */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> RELEASE_CLAIM =
        new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisTokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void blacklist(String jti, long expirationMs) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        long ttlMs = expirationMs - System.currentTimeMillis();
        if (ttlMs <= 0) {
            // Token already expired — no need to store.
            return;
        }
        String key = KEY_PREFIX + jti;
        redisTemplate.opsForValue().set(key, Long.toString(expirationMs), Duration.ofMillis(ttlMs));
        log.debug("[BLACKLIST-REDIS] Token jti={} blacklisted ttlMs={}", jti, ttlMs);
    }

    /** SET NX with the token's remaining lifetime: one winner across every instance. */
    @Override
    public boolean claim(String jti, long expirationMs) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        long ttlMs = expirationMs - System.currentTimeMillis();
        if (ttlMs <= 0) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
            .setIfAbsent(KEY_PREFIX + jti, CLAIM_VALUE_PREFIX + expirationMs, Duration.ofMillis(ttlMs)));
    }

    @Override
    public void markRotated(String jti, long graceMs) {
        if (jti == null || jti.isBlank() || graceMs <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(ROTATED_PREFIX + jti, "1", Duration.ofMillis(graceMs));
    }

    @Override
    public void release(String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        String key = KEY_PREFIX + jti;
        String value = redisTemplate.opsForValue().get(key);
        if (value != null && value.startsWith(CLAIM_VALUE_PREFIX)) {
            redisTemplate.execute(RELEASE_CLAIM, java.util.List.of(key), value);
        }
        redisTemplate.delete(ROTATED_PREFIX + jti);
    }

    @Override
    public boolean wasRecentlyRotated(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(ROTATED_PREFIX + jti));
    }

    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
    }
}
