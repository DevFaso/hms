package com.example.hms.payload.dto.provider;

import com.example.hms.enums.FacilityType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** What the provider portal shell needs at start-up ({@code GET /provider/settings}, provider plan §6.11). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "The settings the provider portal shell reads.")
public class ProviderSettingsDTO {

    /** The facility this request acts at. */
    private UUID facilityId;

    /** PHARMACY or LABORATORY: which pages the shell shows. */
    private FacilityType facilityType;

    /** The caller is the facility's PROVIDER_ADMIN (the Staff and Profile edit pages). */
    private boolean providerAdmin;

    /** {@code provider.organisations.enabled}. */
    private boolean organisationsEnabled;
}
