package com.example.hms.payload.dto.provider;

import com.example.hms.enums.ProviderVerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One verification of a provider facility, as the super-admin's history shows
 * it ({@code GET /super-admin/providers/{id}/verifications}): each submission
 * of evidence and the decision taken on it. The full evidence of the current
 * verification is on {@code ProviderResponseDTO}; a history row names the
 * business and the licence only, so two submissions can be told apart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One verification of a provider facility and the decision taken on it.")
public class ProviderVerificationHistoryEntryDTO {

    private UUID verificationId;
    private ProviderVerificationStatus status;
    private LocalDateTime submittedAt;
    private LocalDateTime decidedAt;
    private UUID decidedByUserId;
    private String decisionReason;
    private String evidenceNote;
    private String legalName;
    private String licenceNumber;
    private String licenceAuthority;
}
