package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One row of a practitioner's credential history (Tier 2 item 40). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CredentialRenewalResponseDTO {

    private UUID id;
    private UUID staffId;

    /**
     * What the licence said immediately before this row. Null means the
     * practitioner had no expiry on file — a first recording rather than a
     * renewal, which is worth distinguishing in the UI.
     */
    private String previousLicenseNumber;
    private LocalDate previousExpiryDate;

    private String licenseNumber;
    private LocalDate expiryDate;
    private String issuingAuthority;
    private String note;

    private UUID recordedByUserId;
    private String recordedByName;
    private LocalDateTime recordedAt;
}
