package com.example.hms.payload.dto.billing;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Billing-money plan PR 6: why a payer is deactivated, or why a plan rule is ended (kept with who and when).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerReasonRequestDTO {

    @Size(max = 500, message = "{billing.payer.reasonSize}")
    private String reason;
}
