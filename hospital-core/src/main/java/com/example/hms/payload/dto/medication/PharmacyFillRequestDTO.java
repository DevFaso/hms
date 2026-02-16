package com.example.hms.payload.dto.medication;

import com.fasterxml.jackson.annotation.JsonFormat;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request DTO for creating or updating pharmacy fill records.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PharmacyFillRequestDTO {

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    /**
     * Optional link to internal prescription if known.
     */
    private UUID prescriptionId;

    // Medication
    @NotBlank(message = "Medication name is required")
    @Size(max = 255)
    private String medicationName;

    @Size(max = 20)
    private String ndcCode;

    @Size(max = 20)
    private String rxnormCode;

    @Size(max = 100)
    private String strength;

    @Size(max = 80)
    private String dosageForm;

    // Dispensing
    @NotNull(message = "Fill date is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate fillDate;

    private BigDecimal quantityDispensed;

    @Size(max = 60)
    private String quantityUnit;

    private Integer daysSupply;

    private Integer refillNumber;

    @Size(max = 1000)
    private String directions;

    // Pharmacy
    @Size(max = 255)
    private String pharmacyName;

    @Size(max = 50)
    private String pharmacyNpi;

    @Size(max = 20)
    private String pharmacyNcpdp;

    @Size(max = 120)
    private String pharmacyPhone;

    @Size(max = 500)
    private String pharmacyAddress;

    // Prescriber
    @Size(max = 255)
    private String prescriberName;

    @Size(max = 50)
    private String prescriberNpi;

    @Size(max = 50)
    private String prescriberDea;

    // Source
    @Size(max = 100)
    private String sourceSystem;

    @Size(max = 255)
    private String externalReferenceId;

    private Boolean controlledSubstance;

    private Boolean genericSubstitution;

    @Size(max = 1000)
    private String notes;
}
