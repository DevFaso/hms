package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.HospitalLifecycleResponseDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleActionRequestDTO;

import java.util.UUID;

/**
 * Hospital-level lifecycle state machine (MVP-c batch).
 *
 * <p>Mirrors {@link OrganizationLifecycleService} so a super admin can
 * suspend / restore / archive / schedule-purge an individual hospital
 * independently of its parent organization. Suspending an organization
 * implicitly blocks login at every hospital under it (handled in
 * {@code JwtAuthenticationFilter}); a hospital lifecycle transition
 * narrows the block to a single facility without touching siblings.
 *
 * <p><b>Step-up MFA</b>: destructive transitions (suspend, archive,
 * schedulePurge) accept an optional {@code mfaToken} captured from an
 * {@code X-Mfa-Token} header — same plumbing as the org-level service.
 */
public interface HospitalLifecycleService {

    HospitalLifecycleResponseDTO getLifecycle(UUID hospitalId);

    HospitalLifecycleResponseDTO suspend(UUID hospitalId, TenantLifecycleActionRequestDTO request, String mfaToken);

    HospitalLifecycleResponseDTO restore(UUID hospitalId, TenantLifecycleActionRequestDTO request);

    HospitalLifecycleResponseDTO archive(UUID hospitalId, TenantLifecycleActionRequestDTO request, String mfaToken);

    HospitalLifecycleResponseDTO schedulePurge(UUID hospitalId, TenantLifecycleActionRequestDTO request, String mfaToken);

    HospitalLifecycleResponseDTO cancelPurge(UUID hospitalId, TenantLifecycleActionRequestDTO request);
}
