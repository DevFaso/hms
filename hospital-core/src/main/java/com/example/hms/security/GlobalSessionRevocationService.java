package com.example.hms.security;

import com.example.hms.model.SecurityRevocation;
import com.example.hms.repository.SecurityRevocationRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * MVP-7: Caches the global minimum-iat timestamp used by the JWT filter
 * to invalidate tokens en masse. Reads from
 * {@link SecurityRevocationRepository} once at startup, refreshes every
 * 30 s, and updates immediately on {@link #revokeAll}.
 *
 * <p>The 30 s ceiling means a force-logout-all takes effect within 30 s
 * across multi-instance deployments without needing a Redis pub/sub
 * channel; a hot bump on the same instance is instantaneous because
 * {@link #revokeAll} updates the volatile field before returning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalSessionRevocationService {

    private static final Instant EPOCH = Instant.EPOCH;

    private final SecurityRevocationRepository repository;

    private volatile Instant cachedGlobalMinTokenIat = EPOCH;

    @PostConstruct
    void init() {
        refresh();
    }

    @Scheduled(fixedDelay = 30_000L)
    public void refresh() {
        try {
            // PR #228 review — fail open when the singleton row is missing
            // (accidental delete / partial rollback) by resetting the cache
            // to EPOCH, matching the class-level "treats absence as
            // iat=epoch" contract. Previously we kept whatever stale value
            // the cache held, which could leave the system permanently
            // locked out after a manual DB cleanup.
            Instant refreshed = repository.findById(SecurityRevocation.SINGLETON_ID)
                .map(SecurityRevocation::getGlobalMinTokenIat)
                .orElse(EPOCH);
            cachedGlobalMinTokenIat = refreshed != null ? refreshed : EPOCH;
        } catch (RuntimeException ex) {
            // Don't let a transient DB blip break authentication. Log and keep
            // the previous cached value — same defensive posture used by
            // IntegrationHealthRecorder (MVP-3).
            log.warn("[REVOCATION] Failed to refresh global min-iat, keeping cached value: {}", ex.getMessage());
        }
    }

    public Instant getGlobalMinTokenIat() {
        return cachedGlobalMinTokenIat;
    }

    @Transactional
    public Instant revokeAll(UUID actorUserId, String actorUsername, String reason) {
        Instant now = Instant.now();
        SecurityRevocation row = repository.findById(SecurityRevocation.SINGLETON_ID)
            .orElseGet(() -> {
                SecurityRevocation seeded = new SecurityRevocation();
                seeded.setId(SecurityRevocation.SINGLETON_ID);
                return seeded;
            });
        row.setGlobalMinTokenIat(now);
        row.setLastRevokedByUserId(actorUserId);
        row.setLastRevokedByUsername(actorUsername);
        row.setLastRevokedReason(reason);
        row.setUpdatedAt(now);
        repository.save(row);
        cachedGlobalMinTokenIat = now;
        return now;
    }
}
