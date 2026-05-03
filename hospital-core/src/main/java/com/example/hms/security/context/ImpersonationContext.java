package com.example.hms.security.context;

import java.util.UUID;

/**
 * Per-request snapshot of "this caller is acting under a support-impersonation
 * token" (MVP-4 — see docs/super-admin-gaps.md). Held on a thread-local by
 * {@link ImpersonationContextHolder} and populated by
 * {@code JwtAuthenticationFilter} from the
 * {@code impersonatorUserId} / {@code impersonatorUsername} JWT claims.
 *
 * <p>The {@code AuditEventLogServiceImpl} reads this context just before
 * persisting an event so every action under impersonation carries the real
 * super admin's identity even though the JWT subject (and therefore the rest
 * of the request) sees the target user.
 */
public record ImpersonationContext(
    UUID impersonatorUserId,
    String impersonatorUsername
) {
    public static ImpersonationContext of(UUID userId, String username) {
        return new ImpersonationContext(userId, username);
    }
}
