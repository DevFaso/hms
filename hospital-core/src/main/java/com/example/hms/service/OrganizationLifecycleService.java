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
 */
public interface OrganizationLifecycleService {

    /** Snapshot of the current lifecycle state. */
    TenantLifecycleResponseDTO getLifecycle(UUID organizationId);

    /** ACTIVE → SUSPENDED. Reason is required. */
    TenantLifecycleResponseDTO suspend(UUID organizationId, TenantLifecycleActionRequestDTO request);

    /** SUSPENDED|ARCHIVED → ACTIVE. Reason is optional. */
    TenantLifecycleResponseDTO restore(UUID organizationId, TenantLifecycleActionRequestDTO request);

    /** ACTIVE|SUSPENDED → ARCHIVED. Reason is required. */
    TenantLifecycleResponseDTO archive(UUID organizationId, TenantLifecycleActionRequestDTO request);

    /** ARCHIVED → PENDING_PURGE. Reason is required; default purge time is now+30d if unspecified. */
    TenantLifecycleResponseDTO schedulePurge(UUID organizationId, TenantLifecycleActionRequestDTO request);

    /** PENDING_PURGE → ARCHIVED. Reason is optional. */
    TenantLifecycleResponseDTO cancelPurge(UUID organizationId, TenantLifecycleActionRequestDTO request);
}
