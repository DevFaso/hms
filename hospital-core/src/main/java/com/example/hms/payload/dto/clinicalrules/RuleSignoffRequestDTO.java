package com.example.hms.payload.dto.clinicalrules;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * V356: « Enregistrer la validation » — the approver's sign-off of one rule set, entered by the hospital admin.
 *
 * @param ruleVersion  the version the approver read and signed; refused when the rule has moved on since
 * @param signerName   the approver's name, as written
 * @param signerTitle  their role or title (« Médecin-chef »)
 * @param signerUserId their account, only when they have one at this hospital
 * @param signedOn     the date they signed (today or earlier)
 * @param note         an optional note
 */
public record RuleSignoffRequestDTO(
    @NotBlank @Size(max = 64) String ruleVersion,
    @NotBlank @Size(max = 200) String signerName,
    @NotBlank @Size(max = 200) String signerTitle,
    UUID signerUserId,
    @NotNull @PastOrPresent LocalDate signedOn,
    @Size(max = 1000) String note) {
}
