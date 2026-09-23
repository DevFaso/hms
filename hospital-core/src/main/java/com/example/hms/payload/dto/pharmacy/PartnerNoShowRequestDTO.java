package com.example.hms.payload.dto.pharmacy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /pharmacy/routing/partner-no-show/{routingDecisionId}}:
 * why the pharmacist is taking an order back from a partner that accepted it
 * and never delivered.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartnerNoShowRequestDTO {

    /** Required — this cancels a partner's claim on a prescription. */
    @NotBlank(message = "{routingDecision.noShow.reason.required}")
    @Size(max = 1024, message = "{routingDecision.reason.size}")
    private String reason;
}
