package com.example.hms.payload.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of the paediatric dose review (round 19, QA D1096): the reviewer's
 * reason and nothing else. Who reviewed and when come from the server, as for
 * the signing, co-signing and pharmacist-verification ceremonies.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PediatricDoseReviewRequestDTO {

    /** Why the reviewer accepts the dose. Mandatory: the service refuses a blank one. */
    @Size(max = 1000)
    private String reason;
}
