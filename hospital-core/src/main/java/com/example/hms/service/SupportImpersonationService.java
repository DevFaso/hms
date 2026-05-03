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
     * header). Throws {@code BusinessException} for self-impersonation,
     * impersonating a super admin (anti-collusion), or nested impersonation.
     */
    ImpersonationStartResponseDTO start(ImpersonationStartRequestDTO request, String mfaToken);

    /**
     * Record IMPERSONATION_ENDED. The frontend discards the impersonation
     * token and restores the original super-admin token from local storage.
     * Returns the closed-out active state for the caller's UX.
     */
    ImpersonationActiveResponseDTO stop();

    /** Reflects the current request's {@code ImpersonationContextHolder} state. */
    ImpersonationActiveResponseDTO getActive();
}
