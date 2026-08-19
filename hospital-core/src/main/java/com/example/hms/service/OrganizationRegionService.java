package com.example.hms.service;

import com.example.hms.enums.OrganizationRegion;
import com.example.hms.payload.dto.superadmin.OrganizationRegionResponseDTO;
import com.example.hms.payload.dto.superadmin.OrganizationRegionUpdateRequestDTO;

import java.util.List;
import java.util.UUID;

/**
 * Data-residency / region-tagging operations for super admins (MVP-9 —
 * gap #9 in docs/super-admin-gaps.md).
 */
public interface OrganizationRegionService {

    /**
     * @return the catalogue of region codes the platform recognises, in a
     *     stable display order driven by the enum declaration order. The
     *     frontend uses this to build the region picker so adding a new
     *     region is a single backend change.
     */
    List<OrganizationRegion> listAvailableRegions();

    /**
     * @return per-organization region snapshot for every organization
     *     visible to a super admin. Sorted by organization name for a
     *     deterministic UI render.
     */
    List<OrganizationRegionResponseDTO> listOrganizationRegions();

    /**
     * Read the current region for one organization. Throws
     * {@code ResourceNotFoundException} if the org does not exist.
     */
    OrganizationRegionResponseDTO getOrganizationRegion(UUID organizationId);

    /**
     * Update the region for one organization, emitting a
     * {@code ORGANIZATION_REGION_UPDATED} audit event with the actor's
     * identity and the previous region in the description so the audit
     * trail is sufficient for compliance review.
     *
     * @return the updated snapshot.
     */
    OrganizationRegionResponseDTO updateOrganizationRegion(UUID organizationId,
                                                           OrganizationRegionUpdateRequestDTO request);
}
