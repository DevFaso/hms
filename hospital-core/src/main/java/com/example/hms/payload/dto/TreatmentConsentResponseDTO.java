package com.example.hms.payload.dto;

import com.example.hms.enums.TreatmentConsentMethod;
import com.example.hms.enums.TreatmentConsentSource;
import com.example.hms.enums.TreatmentConsentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** One consent-to-treat record (P3 #21). Field names are the wire contract. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TreatmentConsentResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private String hospitalName;
    private UUID appointmentId;
    private UUID encounterId;
    private TreatmentConsentStatus status;
    private TreatmentConsentMethod method;
    private TreatmentConsentSource source;
    private String signedName;
    private String signatureHash;
    private LocalDateTime consentedAt;
    private LocalDateTime expiresAt;
    private String recordedByName;
    private LocalDateTime revokedAt;
    private String revocationReason;
    private String notes;
    private LocalDateTime createdAt;
}
