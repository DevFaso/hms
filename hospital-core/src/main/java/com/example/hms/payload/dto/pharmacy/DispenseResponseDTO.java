package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DispenseResponseDTO {

    private UUID id;
    private UUID prescriptionId;
    private UUID patientId;
    private UUID pharmacyId;
    private UUID stockLotId;
    private UUID dispensedBy;
    private UUID verifiedBy;
    private UUID medicationCatalogItemId;
    private String medicationName;
    private BigDecimal quantityRequested;
    private BigDecimal quantityDispensed;
    private String unit;
    private boolean substitution;
    private String substitutionReason;
    private String status;
    private String notes;
    private LocalDateTime dispensedAt;

    /* ── Counter-side verification (Tier 2 item 34) ─────────────────────── */

    /** NOT_VERIFIED / VERIFIED / OVERRIDDEN. */
    private String verificationStatus;

    /** When the scan was performed; null on the paper-fallback path. */
    private LocalDateTime scanVerifiedAt;

    /**
     * Names of the checks that failed and were overridden. Empty unless the
     * status is OVERRIDDEN — a list rather than the raw JSON string the
     * column holds, so the portal renders it without parsing.
     */
    private List<String> verificationOverrides;

    private String verificationOverrideReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
