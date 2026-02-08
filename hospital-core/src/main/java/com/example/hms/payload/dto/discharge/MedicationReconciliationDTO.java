package com.example.hms.payload.dto.discharge;

import com.example.hms.enums.MedicationReconciliationAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.UUID;

/**
 * DTO for medication reconciliation entries in discharge summaries
 * Part of Story #14: Discharge Summary Assembly
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicationReconciliationDTO {

    @NotBlank(message = "Medication name is required")
    @Size(max = 255)
    private String medicationName;

    @Size(max = 64)
    private String medicationCode;

    @Size(max = 100)
    private String dosage;

    @Size(max = 50)
    private String route;

    @Size(max = 100)
    private String frequency;

    @NotNull(message = "Reconciliation action is required")
    private MedicationReconciliationAction reconciliationAction;

    private Boolean wasOnAdmission;
    private Boolean givenDuringHospitalization;
    private Boolean continueAtDischarge;

    @Size(max = 1000)
    private String reasonForChange;

    @Size(max = 1000)
    private String prescriberNotes;

    private UUID prescriptionId;

    @Size(max = 1000)
    private String patientInstructions;
}
