package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PharmacyClaimResponseDTO {

    private UUID id;
    private UUID dispenseId;
    private UUID patientId;
    /** QA D992 class: the claims list names the patient instead of a slice of the id. */
    private String patientName;
    private UUID hospitalId;
    private String coverageReference;
    private String claimStatus;
    private BigDecimal amount;
    private String currency;
    private LocalDateTime submittedAt;
    private UUID submittedBy;
    private String rejectionReason;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /**
     * QA D2925 (r66): the reader may record the payer's answer (accepted, rejected, paid): a money desk or a
     * hospital admin of the claim's hospital. The pharmacy creates and sends; it reads false here.
     */
    private boolean canRecordPayerDecision;
    /**
     * QA D2925 (r66): the claim is ACCEPTED but nobody of a money desk stands behind it (the pharmacy recorded
     * the acceptance itself, before that was refused): it covers no fill until a money desk confirms it.
     */
    private boolean acceptanceUnconfirmed;
}
