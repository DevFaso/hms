package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.PharmacyPaymentMethod;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PharmacyPaymentRequestDTO {

    @NotNull(message = "{pharmacyPayment.dispenseId.required}")
    private UUID dispenseId;

    @NotNull(message = "{pharmacyPayment.patientId.required}")
    private UUID patientId;

    @NotNull(message = "{department.hospital.required}")
    private UUID hospitalId;

    @NotNull(message = "{pharmacyPayment.paymentMethod.required}")
    private PharmacyPaymentMethod paymentMethod;

    @NotNull(message = "{pharmacyPayment.amount.required}")
    private BigDecimal amount;

    @Size(max = 10)
    private String currency;

    @Size(max = 120)
    private String referenceNumber;

    @NotNull(message = "{pharmacyPayment.receivedBy.required}")
    private UUID receivedBy;

    @Size(max = 1000)
    private String notes;
}
