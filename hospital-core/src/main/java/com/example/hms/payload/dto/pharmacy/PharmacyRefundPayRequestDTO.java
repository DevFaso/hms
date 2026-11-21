package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.MobileMoneyOperator;
import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** QA D2676 (r65): the payout of an approved pharmacy refund; a mobile-money payout needs its operator and reference. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PharmacyRefundPayRequestDTO {

    @Size(max = 120)
    @NoCardNumber
    private String reference;

    private MobileMoneyOperator mobileMoneyOperator;
}
