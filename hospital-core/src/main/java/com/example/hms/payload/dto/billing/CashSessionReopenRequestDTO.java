package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Billing-money plan PR 10: why a closed till is reopened (required, refused by name at the edge). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CashSessionReopenRequestDTO {
    @jakarta.validation.constraints.NotBlank(message = "{billing.cashSession.reopenReasonRequired}")
    @jakarta.validation.constraints.Size(max = 500)
    @com.example.hms.validation.NoCardNumber
    private String note;
}
