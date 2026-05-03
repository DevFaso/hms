package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.OrganizationRegion;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /super-admin/organizations/{id}/region}
 * (MVP-9 — gap #9 in docs/super-admin-gaps.md).
 *
 * <p>{@code region} is required so a typo in the JSON path (e.g. an
 * empty body) is rejected with HTTP 400 instead of silently no-oping.
 * {@code reason} is optional but persisted in the audit row when present.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationRegionUpdateRequestDTO {

    @NotNull(message = "Region is required")
    private OrganizationRegion region;

    @Size(max = 1000, message = "Reason must not exceed 1000 characters")
    private String reason;
}
