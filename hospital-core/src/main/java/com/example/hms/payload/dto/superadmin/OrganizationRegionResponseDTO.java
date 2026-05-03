package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.OrganizationRegion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Response payload for region read / update endpoints (MVP-9 — gap #9
 * in docs/super-admin-gaps.md). Carries enough metadata for the
 * super-admin Data Residency console to render a row without a second
 * round-trip.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationRegionResponseDTO {

    private UUID organizationId;
    private String organizationName;
    private String organizationCode;
    private OrganizationRegion region;
}
