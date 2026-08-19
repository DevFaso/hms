package com.example.hms.service;

import com.example.hms.enums.OrganizationRegion;
import com.example.hms.payload.dto.superadmin.RegionPolicyResponseDTO;
import com.example.hms.payload.dto.superadmin.RegionPolicyUpdateRequestDTO;

import java.util.List;

/**
 * Per-region policy resolver + super-admin write surface (MVP-c
 * batch — MVP-9c).
 *
 * <p>The catalogue is seeded by V86 for every {@link OrganizationRegion}
 * code with NULL overrides (the resolver therefore never sees a missing
 * row). Reads are super-admin-only; the convenience getters
 * ({@link #resolveRetentionDays}, {@link #resolveDefaultExportFormat},
 * {@link #resolveTargetDeploymentUrl}) are package-private call paths
 * for the rest of the platform — they take a region, return the
 * override or {@code null} if none.
 */
public interface RegionPolicyService {

    /** Snapshot every region's overrides, ordered by region code. */
    List<RegionPolicyResponseDTO> listAll();

    /** Get one region's snapshot. Throws ResourceNotFound for unseeded codes. */
    RegionPolicyResponseDTO get(OrganizationRegion region);

    /**
     * Update one region's overrides. Audit-emitted as
     * {@code REGION_POLICY_UPDATED}. Null fields in the request clear
     * the corresponding override.
     */
    RegionPolicyResponseDTO update(OrganizationRegion region, RegionPolicyUpdateRequestDTO request);

    /** Convenience for callers that need the retention override only. */
    Integer resolveRetentionDays(OrganizationRegion region);

    /** Convenience for callers that need the export-format default only. */
    String resolveDefaultExportFormat(OrganizationRegion region);

    /** Convenience for callers that need the deployment-routing URL only. */
    String resolveTargetDeploymentUrl(OrganizationRegion region);

    /**
     * Whether a real {@link com.example.hms.service.provisioning.TenantProvisioningClient}
     * is wired into the running deployment. Surfaces the same flag the
     * write path uses to reject {@code targetDeploymentUrl} writes — the
     * UI consults this to disable the column when only the stub is
     * registered, so an operator never types a URL the deployment
     * cannot honour.
     */
    boolean isRemoteProvisioningCapable();
}
