package com.example.hms.payload.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Round 18: the prescriber's re-authentication for signing a controlled-drug
 * prescription ({@code POST /prescriptions/{id}/sign}). Optional for any
 * other drug. The TOTP code when an authenticator is enrolled, else the
 * password. Verified on that call only, for that one signature; never stored
 * and never logged ({@code toString} excludes both).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"password", "mfaCode", "allergyOverrideReason"})
public class SignPrescriptionRequestDTO {

    @Size(max = 256)
    private String password;

    /** A current six-digit TOTP code, or an unused backup code. */
    @Size(max = 32)
    private String mfaCode;

    /**
     * QA D1183 (round 23): the prescriber's reason for signing an order the
     * allergy stop applies to (a documented allergy covers the drug, or the
     * drug name could not be checked against a drug-class allergy) when no
     * acknowledgement was recorded at creation or edit. Stored on the order
     * and audited as a digest, like the creation override.
     */
    @Size(max = 1024)
    private String allergyOverrideReason;
}
