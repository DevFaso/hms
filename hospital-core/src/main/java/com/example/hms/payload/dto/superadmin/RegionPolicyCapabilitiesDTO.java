package com.example.hms.payload.dto.superadmin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only capability flags for the region-policy editor (MVP-c3 —
 * provisioning guard). The UI consults these to decide whether the
 * {@code targetDeploymentUrl} column is editable; the same flags back
 * the server-side write guard so the two layers cannot drift.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Capability flags governing what fields the region-policy editor may write.")
public class RegionPolicyCapabilitiesDTO {

    @Schema(description = "True when a real TenantProvisioningClient bean is wired into the running deployment.")
    private boolean remoteProvisioningCapable;
}
