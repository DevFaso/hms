package com.example.hms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token blacklist backed by a {@link ConcurrentHashMap}.
 * Expired entries are evicted every 5 minutes by a scheduled task.
 *
 * <p><strong>Limitation:</strong> state is lost on restart and not shared
 * across instances.  Replace with a Redis-backed implementation when
 * horizontal scaling is required.
 */
@Slf4j
@Service
public class InMemoryTokenBlacklistService implements TokenBlacklistService {

    /** jti → expiration epoch-millis */
    private final Map<String, Long> blacklistedTokens = new ConcurrentHashMap<>();

    @Override
    public void blacklist(String jti, long expirationMs) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        blacklistedTokens.put(jti, expirationMs);
        log.debug("[BLACKLIST] Token jti={} blacklisted until {}", jti, expirationMs);
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
        int evicted = before - blacklistedTokens.size();
        if (evicted > 0) {
            log.info("[BLACKLIST] Evicted {} expired entries, {} remaining", evicted, blacklistedTokens.size());
        }
    }
}
