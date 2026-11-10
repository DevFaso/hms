package com.example.hms.payload.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of the pharmacist-verification ceremony (Tier 2 item 33).
 *
 * <p>Carries a note and nothing else. Who verified and when are taken from
 * the server, never from the client — the same stance the signing and
 * co-signing ceremonies take, and the reason those DTOs also refuse
 * client-asserted identity and timestamps.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PharmacistVerificationRequestDTO {

    /**
     * Optional free text — what the pharmacist checked, or a caveat worth
     * putting in front of the nurse. Optional because the common case is a
     * clean check with nothing to add, and forcing a note there would train
     * people to type "ok".
     */
    @Size(max = 1000)
    private String note;

    /**
     * QA D1563 / D1555: the reason for going ahead although the patient's current allergies stop this
     * drug, after reading the allergy card ({@code CurrentAllergyRecheck}). Required only then; kept
     * on the record and audited.
     */
    @Size(max = 500)
    private String allergyAcknowledgementReason;

    /** QA D1563 / D1555: the token the allergy card carried ({@code exceptionKey}): which allergy was acknowledged. */
    @Size(max = 200)
    private String allergyAcknowledgementToken;
}
