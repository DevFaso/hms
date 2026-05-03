package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.ImpersonationActiveResponseDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartRequestDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartResponseDTO;

/**
 * Support-impersonation surface for super admins (MVP-4 — see
 * docs/super-admin-gaps.md).
 *
 * <p>An active super admin can mint a short-lived JWT that represents
 * another (non-super-admin) user. Every action taken under that token is
 * audited with the real super admin captured in the new
 * {@code impersonator_user_id} / {@code impersonator_username} columns
 * added by V79.
 */
public interface SupportImpersonationService {

    /**
     * Mint an impersonation token. Must be called by an authenticated super
     * admin who is NOT already impersonating. MFA step-up is required when
     * the actor has MFA enrolled (verified against the {@code X-Mfa-Token}
     * header). The current bearer token (the super admin's original access
     * token) is blacklisted as part of this call so a remembered-session
     * client cannot keep using it from {@code localStorage} (closes Copilot
     * review #2 on PR #224). The session is also registered in the
     * {@link com.example.hms.security.ImpersonationSessionTracker} so a
     * subsequent /auth/token/refresh on the original cookie is refused —
     * closing the privilege-escalation hole flagged as Copilot review #4.
     *
     * @param request   target user id + reason
     * @param mfaToken  X-Mfa-Token header value (may be null when MFA not enrolled)
     * @param bearerJwt current request's raw bearer token (the original super-
     *                  admin access token), blacklisted on success
     */
    ImpersonationStartResponseDTO start(ImpersonationStartRequestDTO request,
                                        String mfaToken,
                                        String bearerJwt);

    /**
     * Record IMPERSONATION_ENDED, blacklist the impersonation JWT (closes
     * Copilot review #6 on PR #224 — a copied impersonation token must not
     * keep authenticating after the super admin clicks Exit), and unregister
     * the tracker entry so the super admin's refresh cookie can mint new
     * super-admin access tokens again.
     *
     * @param bearerJwt current request's raw bearer token (the impersonation
     *                  token that should be blacklisted)
     */
    ImpersonationActiveResponseDTO stop(String bearerJwt);

    /** Reflects the current request's {@code ImpersonationContextHolder} state. */
    ImpersonationActiveResponseDTO getActive();
}
