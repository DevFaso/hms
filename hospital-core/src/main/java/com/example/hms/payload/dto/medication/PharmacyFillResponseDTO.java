package com.example.hms.payload.dto.medication;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for pharmacy fill records.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PharmacyFillResponseDTO {

    private UUID id;

    // Patient
    private UUID patientId;
    private String patientName;
    private String patientMrn;

    // Hospital
    private UUID hospitalId;
    private String hospitalName;

    // Prescription link
    private UUID prescriptionId;

    // Medication
    private String medicationName;
    private String ndcCode;
    private String rxnormCode;
    private String strength;
    private String dosageForm;

    // Dispensing
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate fillDate;

    private BigDecimal quantityDispensed;
    private String quantityUnit;
    private Integer daysSupply;
    private Integer refillNumber;
    private String directions;

    // Calculated
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expectedDepletionDate; // fillDate + daysSupply

    // Pharmacy
    private String pharmacyName;
    private String pharmacyNpi;
    private String pharmacyNcpdp;
    private String pharmacyPhone;
    private String pharmacyAddress;

    // Prescriber
    private String prescriberName;
    private String prescriberNpi;
    private String prescriberDea;

    // Source
    private String sourceSystem;
    private String externalReferenceId;

    private boolean controlledSubstance;
    private boolean genericSubstitution;

    private String notes;

    // Audit
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
