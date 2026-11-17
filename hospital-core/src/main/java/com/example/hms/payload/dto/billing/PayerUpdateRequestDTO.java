package com.example.hms.payload.dto.billing;

import com.example.hms.enums.PayerType;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Billing-money plan PR 6: a payer's name and type; its code never changes.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayerUpdateRequestDTO {

    @Size(max = 150, message = "{billing.payer.nameSize}")
    private String name;
    private PayerType payerType;
}
