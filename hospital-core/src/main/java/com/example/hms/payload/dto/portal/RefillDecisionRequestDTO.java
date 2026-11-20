package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Provider decision on a medication refill request (approve or reject).")
public class RefillDecisionRequestDTO {

    /** QA D1315 (round 29): required to deny or hold (the patient is told why); optional to approve. */
    @jakarta.validation.constraints.NotBlank(groups = com.example.hms.validation.OnRefusal.class,
            message = "{patient.refill.decision.reasonRequired}")
    @Size(max = 1000)
    @Schema(description = "Provider notes shown to the patient; required to deny or hold shown to the patient (e.g. reason for denial).",
            example = "Approved — please pick up at preferred pharmacy by Friday.")
    private String providerNotes;

    /**
     * Policy rule 1 (QA D2308): what the pharmacist writes: the flags forwarded to the prescriber, or the reason of
     * a refusal to dispense. Required on those two steps, ignored on a prescriber's decision.
     */
    @Size(max = 1000)
    private String pharmacistNote;
}
