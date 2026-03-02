package com.example.hms.payload.dto;

import com.example.hms.enums.ShareScope;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Envelope returned by the smart record-sharing resolver.
 *
 * <p>In addition to the full {@link PatientRecordDTO} payload it carries
 * metadata about <em>how</em> the share was resolved so the UI can render
 * the correct trust-level badge and provenance trail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of the smart record-sharing resolution, including scope metadata.")
public class RecordShareResultDTO {

    // -----------------------------------------------------------------------
    // Scope / provenance
    // -----------------------------------------------------------------------

    @Schema(description = "Resolution tier: SAME_HOSPITAL | INTRA_ORG | CROSS_ORG",
            example = "INTRA_ORG")
    private ShareScope shareScope;

    @Schema(description = "Human-readable label for the scope tier.",
            example = "Intra-organisation share")
    private String shareScopeLabel;

    @Schema(description = "ID of the hospital that is the source of the records.")
    private UUID resolvedFromHospitalId;

    @Schema(description = "Name of the source hospital.", example = "Ouagadougou General Hospital")
    private String resolvedFromHospitalName;

    @Schema(description = "ID of the requesting / destination hospital.")
    private UUID requestingHospitalId;

    @Schema(description = "Name of the requesting hospital.", example = "Bobo-Dioulasso Central")
    private String requestingHospitalName;

    @Schema(description = "Name of the shared organisation (non-null for INTRA_ORG).",
            example = "HealthNet Burkina")
    private String organizationName;

    @Schema(description = "ID of the organisation (non-null for INTRA_ORG).")
    private UUID organizationId;

    // -----------------------------------------------------------------------
    // Consent metadata
    // -----------------------------------------------------------------------

    @Schema(description = "ID of the consent record that authorised this share.")
    private UUID consentId;

    @Schema(description = "When consent was granted.")
    private LocalDateTime consentGrantedAt;

    @Schema(description = "When this consent expires (null = no expiry).")
    private LocalDateTime consentExpiresAt;

    @Schema(description = "Purpose recorded on the consent.", example = "Emergency consultation")
    private String consentPurpose;

    @Schema(description = "Whether the consent is still active.")
    private boolean consentActive;

    // -----------------------------------------------------------------------
    // Audit
    // -----------------------------------------------------------------------

    @Schema(description = "Timestamp when this result was generated (server time).")
    private LocalDateTime resolvedAt;

    // -----------------------------------------------------------------------
    // Full record payload
    // -----------------------------------------------------------------------

    @Schema(description = "The full patient record, scoped to the resolved source hospital.")
    private PatientRecordDTO patientRecord;
}
