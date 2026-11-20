package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.MobileMoneyOperator;
import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * QA D2676 (r65): {@code POST /pharmacy/refunds}, money given back for a patient return. The amount is never sent:
 * the server computes it from the returned quantity and the price the patient paid, bounded by what was paid.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PharmacyRefundRequestDTO {

    /** The RETURN stock movement that put the medication back (it names the sale or the dispense). */
    @NotNull
    private UUID returnTransactionId;

    /** How the money goes back; the original payment's method when left out. Never INSURANCE. */
    private PharmacyPaymentMethod method;

    private MobileMoneyOperator mobileMoneyOperator;

    @Size(max = 120)
    @NoCardNumber
    private String reference;

    @Size(max = 500)
    @NoCardNumber
    private String reason;
}
