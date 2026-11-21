package com.example.hms.payload.dto.recordaccess;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Policy 2026-11-20: release one sensitive result of a minor to one parent's
 * or guardian's grant.
 *
 * @param grantId              the family-access grant of the parent or guardian
 * @param reason               why the result is released (counselling done, the parents' request...)
 * @param relationshipVerified the clinician attests that they checked the holder is the patient's
 *                             parent or legal guardian; required when the grant is not verified yet
 */
public record MinorResultReleaseRequestDTO(
    @NotNull UUID grantId,
    // r66 D2904: one sentence that says what to write, not « motif : la taille doit être comprise entre 10 et 1000 ».
    @NotBlank(message = "{proxyRelease.reason.length}")
    @Size(min = 10, max = 1000, message = "{proxyRelease.reason.length}") String reason,
    Boolean relationshipVerified
) {
}
