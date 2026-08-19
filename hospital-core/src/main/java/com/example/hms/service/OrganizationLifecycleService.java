package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.TenantLifecycleActionRequestDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleResponseDTO;

import java.util.UUID;

/**
 * Tenant lifecycle state-machine for organizations (MVP-2).
 *
 * <p>All transitions emit an {@code AuditEventLog} entry and return the
 * post-transition snapshot. Implementations enforce the state-machine rules
 * documented on {@link com.example.hms.enums.OrganizationLifecycleState}.
 *
 * <p><b>Step-up MFA</b>: destructive transitions (suspend, archive,
 * schedulePurge) accept an optional {@code mfaToken} captured from an
 * {@code X-Mfa-Token} header. When the actor has MFA enrolled and
 * {@code hms.tenant-lifecycle.require-mfa} is enabled (default true), the
 * transition is rejected with
 * {@link com.example.hms.exception.UnauthorizedException} if the token is
 * missing or invalid. Restore and cancel-purge are non-destructive and not
 * gated by MFA.
 */
public interface OrganizationLifecycleService {

    /** Snapshot of the current lifecycle state. */
    TenantLifecycleResponseDTO getLifecycle(UUID organizationId);

    /** ACTIVE → SUSPENDED. Reason required; MFA step-up enforced when enabled. */
    TenantLifecycleResponseDTO suspend(UUID organizationId, TenantLifecycleActionRequestDTO request, String mfaToken);

    /** SUSPENDED|ARCHIVED → ACTIVE. Reason is optional. */
    TenantLifecycleResponseDTO restore(UUID organizationId, TenantLifecycleActionRequestDTO request);

    /** ACTIVE|SUSPENDED → ARCHIVED. Reason required; MFA step-up enforced when enabled. */
    TenantLifecycleResponseDTO archive(UUID organizationId, TenantLifecycleActionRequestDTO request, String mfaToken);

    /** ARCHIVED → PENDING_PURGE. Reason required; default purge time is now+30d. MFA step-up enforced when enabled. */
    TenantLifecycleResponseDTO schedulePurge(UUID organizationId, TenantLifecycleActionRequestDTO request, String mfaToken);

    /** PENDING_PURGE → ARCHIVED. Reason is optional. */
    TenantLifecycleResponseDTO cancelPurge(UUID organizationId, TenantLifecycleActionRequestDTO request);
}
