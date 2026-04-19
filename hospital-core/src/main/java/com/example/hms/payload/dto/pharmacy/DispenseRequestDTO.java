package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
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
public class DispenseRequestDTO {

    @NotNull(message = "Prescription ID is required")
    private UUID prescriptionId;

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Pharmacy ID is required")
    private UUID pharmacyId;

    private UUID stockLotId;

    @NotNull(message = "Dispensed-by user ID is required")
    private UUID dispensedBy;

    private UUID verifiedBy;

    private UUID medicationCatalogItemId;

    @NotBlank(message = "Medication name is required")
    @Size(max = 255)
    private String medicationName;

    @NotNull(message = "Quantity requested is required")
    private BigDecimal quantityRequested;

    @NotNull(message = "Quantity dispensed is required")
    private BigDecimal quantityDispensed;

    @Size(max = 60)
    private String unit;

    private Boolean substitution;

    @Size(max = 500)
    private String substitutionReason;

    private DispenseStatus status;

    @Size(max = 1000)
    private String notes;
}
