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
@ToString(exclude = {"password", "mfaCode"})
public class SignPrescriptionRequestDTO {

    @Size(max = 256)
    private String password;

    /** A current six-digit TOTP code, or an unused backup code. */
    @Size(max = 32)
    private String mfaCode;
}
