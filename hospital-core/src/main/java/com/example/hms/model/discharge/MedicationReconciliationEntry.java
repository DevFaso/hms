package com.example.hms.model.discharge;

import com.example.hms.enums.MedicationReconciliationAction;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * Embeddable class for tracking medication changes from admission to discharge
 * Part of Story #14: Discharge Summary Assembly
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class MedicationReconciliationEntry {

    @Column(name = "medication_name", nullable = false, length = 255)
    private String medicationName;

    @Column(name = "medication_code", length = 64)
    private String medicationCode;

    @Column(name = "dosage", length = 100)
    private String dosage;

    @Column(name = "route", length = 50)
    private String route;

    @Column(name = "frequency", length = 100)
    private String frequency;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_action", nullable = false, length = 30)
    private MedicationReconciliationAction reconciliationAction;

    @Column(name = "was_on_admission")
    private Boolean wasOnAdmission;

    @Column(name = "given_during_hospitalization")
    private Boolean givenDuringHospitalization;

    @Column(name = "continue_at_discharge")
    private Boolean continueAtDischarge;

    @Column(name = "reason_for_change", length = 1000)
    private String reasonForChange;

    @Column(name = "prescriber_notes", length = 1000)
    private String prescriberNotes;

    // Link to prescription if this is a newly prescribed medication
    @Column(name = "prescription_id")
    private UUID prescriptionId;

    @Column(name = "patient_instructions", length = 1000)
    private String patientInstructions;
}
