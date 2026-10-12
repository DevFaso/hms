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

    /**
     * A token issued at {@code issuedAt} predates the last force-logout-all.
     * No revocation yet, or a token without an {@code iat}, is not revoked.
     *
     * <p>A JWT {@code iat} has whole-second precision. {@link #revokeAll}
     * therefore stores its cut-off rounded UP to the next whole second, and
     * this compares strictly: every token minted in the revocation's second,
     * before it or just after, is revoked. A kill switch errs on the safe
     * side; a session started in that same second signs in once more.
     */
    public boolean revokes(java.util.Date issuedAt) {
        Instant minIat = cachedGlobalMinTokenIat;
        return issuedAt != null && minIat != null && !EPOCH.equals(minIat)
            && issuedAt.toInstant().isBefore(minIat);
    }

    /**
     * {@link #revokes} against the row itself, not the 30-second cache: for
     * the rare paths that MINT a session (refresh), where a lagging instance
     * must not issue a post-cut-off pair from a token the kill switch revoked.
     */
    public boolean revokesFresh(java.util.Date issuedAt) {
        // Read the row WITHOUT writing it into the cache: a revokeAll on this
        // instance may have put a newer cut-off there before its commit, and
        // the row would carry it back. The later of the two decides.
        Instant minIat = cachedGlobalMinTokenIat;
        try {
            Instant row = repository.findById(SecurityRevocation.SINGLETON_ID)
                .map(SecurityRevocation::getGlobalMinTokenIat)
                .orElse(null);
            if (row != null && (minIat == null || row.isAfter(minIat))) {
                minIat = row;
            }
        } catch (RuntimeException ex) {
            log.warn("[REVOCATION] Fresh read failed, using the cached cut-off: {}", ex.getMessage());
        }
        return issuedAt != null && minIat != null && !EPOCH.equals(minIat)
            && issuedAt.toInstant().isBefore(minIat);
    }

    /** The cut-off for a revocation at {@code at}: the next whole second (a JWT iat is whole seconds). */
    static Instant cutOff(Instant at) {
        Instant second = at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return second.equals(at) ? at : second.plusSeconds(1);
    }

    @Transactional
    public Instant revokeAll(UUID actorUserId, String actorUsername, String reason) {
        // The revocation happened now; only the token cut-off rounds up.
        Instant now = Instant.now();
        Instant minIat = cutOff(now);
        SecurityRevocation row = repository.findById(SecurityRevocation.SINGLETON_ID)
            .orElseGet(() -> {
                SecurityRevocation seeded = new SecurityRevocation();
                seeded.setId(SecurityRevocation.SINGLETON_ID);
                return seeded;
            });
        row.setGlobalMinTokenIat(minIat);
        row.setLastRevokedByUserId(actorUserId);
        row.setLastRevokedByUsername(actorUsername);
        row.setLastRevokedReason(reason);
        row.setUpdatedAt(now);
        repository.save(row);
        cachedGlobalMinTokenIat = minIat;
        return now;
    }
}
