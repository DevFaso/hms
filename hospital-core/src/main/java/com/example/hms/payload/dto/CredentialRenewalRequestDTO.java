package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Body of the credential-renewal ceremony (Tier 2 item 40).
 *
 * <p>Who recorded it and when are taken from the server, never the client —
 * the same stance the signing, co-signing and pharmacist-verification DTOs
 * take, and for the same reason.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CredentialRenewalRequestDTO {

    /**
     * The new expiry. Required: a renewal with no end date is not a renewal,
     * it is a deletion of the expiry rule.
     */
    @NotNull
    private LocalDate expiryDate;

    /**
     * Null keeps the number already on file. Most renewals reissue the same
     * number, and making an administrator retype it is how a digit gets lost.
     */
    @Size(max = 100)
    private String licenseNumber;

    /** Null keeps what is on file. */
    @Size(max = 200)
    private String issuingAuthority;

    @Size(max = 1000)
    private String note;
}
