package com.example.hms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fallback for {@link IdleSessionTracker} when Redis-backed
 * idle tracking is not enabled. Mirrors the
 * {@link InMemoryTokenBlacklistService} pattern: ConcurrentHashMap +
 * scheduled evictor.
 *
 * <p><strong>Default fallback only.</strong> Active when
 * {@code app.auth.idle-tracking.enabled} is {@code false} or unset.
 * Production deployments must enable the Redis-backed implementation by
 * setting {@code app.auth.idle-tracking.enabled=true}: in-memory state is
 * lost on restart and is not shared across instances, which means the
 * idle gate becomes per-node and inconsistent across a horizontally
 * scaled deployment.
 *
 * <p>Eviction runs every five minutes; the touch path stamps a value of
 * {@code touchAtMs + idleWindowMs} so {@link #isIdle(UUID)} is a single
 * map lookup + comparison.
 *
 * <p>Added in v1.0 / Security / Idle session timeout (roadmap row 7).
 */
@Slf4j
@Service
@ConditionalOnProperty(
    name = "app.auth.idle-tracking.enabled",
    havingValue = "false",
    matchIfMissing = true)
public class InMemoryIdleSessionTracker implements IdleSessionTracker {

    /** userId → epoch-millis at which the entry should be considered idle. */
    private final Map<UUID, Long> expiresAt = new ConcurrentHashMap<>();

    private final Duration idleWindow;

    public InMemoryIdleSessionTracker(@Value("${app.auth.idle-window:PT15M}") Duration idleWindow) {
        this.idleWindow = idleWindow;
    }

    @Override
    public boolean isEnabled() {
        // The single-node in-memory fallback is operationally usable only
        // for local development and tests. Returning true would let
        // IdleSessionGate apply the gate; we return true so feature parity
        // works in tests, but production deployments must opt into the
        // Redis impl by flipping app.auth.idle-tracking.enabled=true.
        return true;
    }

    @Override
    public void touch(UUID userId) {
        if (userId == null) return;
        expiresAt.put(userId, System.currentTimeMillis() + idleWindow.toMillis());
    }

    @Override
    public boolean isIdle(UUID userId) {
        if (userId == null) return false;
        Long expiry = expiresAt.get(userId);
        if (expiry == null) return true;
        if (expiry <= System.currentTimeMillis()) {
            // Lazy cleanup so the next touch starts fresh.
            expiresAt.remove(userId, expiry);
            return true;
        }
        return false;
    }

    @Override
    public void clear(UUID userId) {
        if (userId == null) return;
        expiresAt.remove(userId);
    }

    /** Evict entries whose idle window has already lapsed. Runs every 5 minutes. */
    @Scheduled(fixedRate = 300_000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        int before = expiresAt.size();
        expiresAt.entrySet().removeIf(e -> e.getValue() <= now);
        int evicted = before - expiresAt.size();
        if (evicted > 0) {
            log.debug("[IDLE-TRACKER-MEM] Evicted {} expired entries, {} remaining",
                evicted, expiresAt.size());
        }
    }
}
