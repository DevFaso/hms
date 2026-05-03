package com.example.hms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of currently-active support-impersonation sessions
 * (MVP-4 — see docs/super-admin-gaps.md, Copilot review fix #4).
 *
 * <p>A super admin can hold at most one session at a time, so the map is
 * keyed on {@code impersonatorUserId}. Three call sites consult the
 * tracker:
 *
 * <ul>
 *   <li>{@code SupportImpersonationServiceImpl.start} — rejects a new
 *       session when one is already active for the caller, then registers
 *       the new one after the original access token's JTI has been
 *       blacklisted.</li>
 *   <li>{@code SupportImpersonationServiceImpl.stop} — unregisters the
 *       session and blacklists the impersonation token's JTI.</li>
 *   <li>{@code AuthController.refreshToken} — refuses to mint a new
 *       super-admin access token from the original refresh cookie while
 *       the user has an active impersonation session, closing the
 *       privilege-escalation hole where a 401 on the impersonation
 *       token would otherwise auto-refresh into a fresh super-admin
 *       token without an {@code IMPERSONATION_ENDED} audit boundary.</li>
 * </ul>
 *
 * <p>Entries are evicted lazily on read once their {@code expiresAt} has
 * passed. This keeps the implementation lock-free and avoids a scheduled
 * sweep — the worst case is one stale entry sitting in memory until the
 * next read for that user, which is fine because every consumer
 * re-checks {@code expiresAt} before treating the session as active.
 */
@Slf4j
@Component
public class ImpersonationSessionTracker {

    private final Map<UUID, ImpersonationSessionInfo> active = new ConcurrentHashMap<>();

    /**
     * Register a new impersonation session. Returns {@code false} if the
     * caller already has an active (non-expired) session — the caller
     * should reject the start request rather than overwriting.
     */
    public boolean register(UUID impersonatorUserId,
                            UUID targetUserId,
                            String impersonationTokenJti,
                            Instant expiresAt) {
        Objects.requireNonNull(impersonatorUserId, "impersonatorUserId is required");
        Objects.requireNonNull(targetUserId, "targetUserId is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        cleanupIfExpired(impersonatorUserId);
        ImpersonationSessionInfo created = new ImpersonationSessionInfo(
            impersonatorUserId, targetUserId, impersonationTokenJti, expiresAt);
        ImpersonationSessionInfo prior = active.putIfAbsent(impersonatorUserId, created);
        if (prior != null) {
            log.warn("[IMPERSONATION] Refused to register a second session for impersonator {} — existing session targets {} (expires {})",
                impersonatorUserId, prior.targetUserId(), prior.expiresAt());
            return false;
        }
        return true;
    }

    /** Remove the impersonator's active session if any. Idempotent. */
    public void unregister(UUID impersonatorUserId) {
        if (impersonatorUserId == null) return;
        active.remove(impersonatorUserId);
    }

    /** Whether {@code userId} has a non-expired impersonation session. */
    public boolean hasActive(UUID userId) {
        if (userId == null) return false;
        cleanupIfExpired(userId);
        return active.containsKey(userId);
    }

    public Optional<ImpersonationSessionInfo> get(UUID userId) {
        if (userId == null) return Optional.empty();
        cleanupIfExpired(userId);
        return Optional.ofNullable(active.get(userId));
    }

    private void cleanupIfExpired(UUID userId) {
        ImpersonationSessionInfo info = active.get(userId);
        if (info != null && Instant.now().isAfter(info.expiresAt())) {
            active.remove(userId, info);
        }
    }

    public record ImpersonationSessionInfo(
        UUID impersonatorUserId,
        UUID targetUserId,
        String impersonationTokenJti,
        Instant expiresAt
    ) {}
}
