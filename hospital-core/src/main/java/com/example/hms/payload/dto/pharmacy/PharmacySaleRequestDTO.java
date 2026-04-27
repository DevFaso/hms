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

    @NotNull(message = "Pharmacy ID is required")
    private UUID pharmacyId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    /** Nullable: anonymous walk-in sales are permitted. */
    private UUID patientId;

    @NotNull(message = "Payment method is required")
    private PharmacyPaymentMethod paymentMethod;

    @Size(max = 10)
    private String currency;

    @Size(max = 120)
    private String referenceNumber;

    @Size(max = 1000)
    private String notes;

    @Valid
    @NotEmpty(message = "At least one sale line is required")
    private List<SaleLineRequestDTO> lines;
}
