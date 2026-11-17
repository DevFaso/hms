package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.PharmacyPaymentMethod;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PharmacySaleRequestDTO {

    @NotNull(message = "{pharmacySale.pharmacyId.required}")
    private UUID pharmacyId;

    @NotNull(message = "{department.hospital.required}")
    private UUID hospitalId;

    /** Nullable: anonymous walk-in sales are permitted. */
    private UUID patientId;

    @NotNull(message = "{pharmacySale.paymentMethod.required}")
    private PharmacyPaymentMethod paymentMethod;

    @Size(max = 10)
    private String currency;

    @Size(max = 120)
    @com.example.hms.validation.NoCardNumber
    private String referenceNumber;

    /**
     * Billing plan PR 9: Orange Money, Moov Money or another operator. Required
     * when the method is MOBILE_MONEY (manual entry, no operator integration).
     */
    private com.example.hms.enums.MobileMoneyOperator mobileMoneyOperator;

    @Size(max = 1000)
    @com.example.hms.validation.NoCardNumber
    private String notes;

    @Valid
    @NotEmpty(message = "{pharmacySale.lines.required}")
    private List<SaleLineRequestDTO> lines;
}
