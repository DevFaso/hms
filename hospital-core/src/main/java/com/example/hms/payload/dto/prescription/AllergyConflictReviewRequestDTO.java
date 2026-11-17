package com.example.hms.payload.dto.prescription;

import com.example.hms.enums.AllergyReviewDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Policy 4 (user recommendation 2026-11-10, pending clinical approval): an authorized prescriber's
 * review of an allergy conflict on a signed order. Who reviewed and when come from the server, as for
 * the signing and the paediatric dose review.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AllergyConflictReviewRequestDTO {

    /** CONTINUE (with a plan) or STOP. Mandatory. */
    @NotNull
    private AllergyReviewDecision decision;

    /** The plan a CONTINUE sets (monitoring, premedication). Mandatory for CONTINUE. */
    @Size(max = 1000)
    private String plan;

    /** The clinical rationale. Mandatory. */
    @NotBlank
    @Size(max = 1000)
    private String rationale;

    /**
     * The conflict the prescriber was shown ({@code reviewToken} of GET .../allergy-conflict): the
     * confirmation that this allergy, on this order as it stands, is the one reviewed.
     */
    @Size(max = 200)
    private String allergyToken;
}
