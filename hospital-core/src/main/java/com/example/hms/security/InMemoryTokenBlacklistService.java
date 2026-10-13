package com.example.hms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token blacklist backed by a {@link ConcurrentHashMap}.
 * Expired entries are evicted every 5 minutes by a scheduled task.
 *
 * <p><strong>Default fallback only.</strong> Active when
 * {@code app.redis.token-blacklist.enabled} is {@code false} or unset.
 *
 * <p><strong>Limitation:</strong> state is lost on restart and is not shared
 * across instances. Production deployments must enable the Redis-backed
 * implementation ({@link RedisTokenBlacklistService}) by setting
 * {@code app.redis.token-blacklist.enabled=true}.
 */
@Slf4j
@Service
@ConditionalOnProperty(
        name = "app.redis.token-blacklist.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class InMemoryTokenBlacklistService implements TokenBlacklistService {

    /** jti → expiration epoch-millis */
    private final Map<String, Long> blacklistedTokens = new ConcurrentHashMap<>();
    /** jti → end of its rotation grace, epoch-millis */
    private final Map<String, Long> recentlyRotated = new ConcurrentHashMap<>();
    /** jtis whose entry a claim wrote and no revocation has overwritten since. */
    private final java.util.Set<String> claimedOnly = ConcurrentHashMap.newKeySet();

    @Override
    public synchronized void blacklist(String jti, long expirationMs) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        blacklistedTokens.put(jti, expirationMs);
        claimedOnly.remove(jti);
        log.debug("[BLACKLIST] Token jti={} blacklisted until {}", jti, expirationMs);
    }

    /** Atomic within this JVM only: a multi-instance deployment needs the Redis store. */
    @Override
    public synchronized boolean claim(String jti, long expirationMs) {
        if (jti == null || jti.isBlank() || expirationMs <= System.currentTimeMillis()) {
            return false;
        }
        if (blacklistedTokens.putIfAbsent(jti, expirationMs) != null) {
            return false;
        }
        claimedOnly.add(jti);
        return true;
    }

    @Override
    public void markRotated(String jti, long graceMs) {
        if (jti == null || jti.isBlank() || graceMs <= 0) {
            return;
        }
        recentlyRotated.put(jti, System.currentTimeMillis() + graceMs);
    }

    @Override
    public synchronized void release(String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        if (claimedOnly.remove(jti)) {
            blacklistedTokens.remove(jti);
        }
        recentlyRotated.remove(jti);
    }

    @Override
    public boolean wasRecentlyRotated(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        Long until = recentlyRotated.get(jti);
        return until != null && until > System.currentTimeMillis();
    }

    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return blacklistedTokens.containsKey(jti);
    }

    /**
     * Evict entries whose underlying tokens have already expired.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300_000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        int before = blacklistedTokens.size();
        blacklistedTokens.entrySet().removeIf(e -> e.getValue() <= now);
        recentlyRotated.entrySet().removeIf(e -> e.getValue() <= now);
        claimedOnly.removeIf(jti -> !blacklistedTokens.containsKey(jti));
        int evicted = before - blacklistedTokens.size();
        if (evicted > 0) {
            log.info("[BLACKLIST] Evicted {} expired entries, {} remaining", evicted, blacklistedTokens.size());
        }
    }
}
