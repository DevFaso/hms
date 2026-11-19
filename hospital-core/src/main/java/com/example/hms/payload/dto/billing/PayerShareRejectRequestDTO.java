package com.example.hms.payload.dto.billing;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Billing-money plan PR 7: the payer's reason for rejecting its share of an invoice ("Refusé par l'assureur :
 * motif"), as typed. Required (the service names it when blank); never a card number.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerShareRejectRequestDTO {

    @Size(max = 500, message = "{billing.payerShare.reasonTooLong}")
    @NoCardNumber
    private String reason;
}
