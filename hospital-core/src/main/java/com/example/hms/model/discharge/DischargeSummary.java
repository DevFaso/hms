package com.example.hms.model.discharge;

import com.example.hms.enums.DischargeDisposition;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.DischargeApproval;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.OrderColumn;

/**
 * Comprehensive Discharge Summary for Story #14
 * Tracks structured discharge instructions, medication reconciliation, pending tests, and patient education
 */
@Entity
@Table(
    name = "discharge_summaries",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_discharge_summary_patient", columnList = "patient_id"),
        @Index(name = "idx_discharge_summary_encounter", columnList = "encounter_id"),
        @Index(name = "idx_discharge_summary_hospital", columnList = "hospital_id"),
        @Index(name = "idx_discharge_summary_discharge_date", columnList = "discharge_date"),
        @Index(name = "idx_discharge_summary_created_at", columnList = "createdAt")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"patient", "encounter", "hospital", "dischargingProvider", "assignment", "approvalRecord"})
public class DischargeSummary extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_discharge_summary_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_discharge_summary_encounter"))
    private Encounter encounter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_discharge_summary_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discharging_provider_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_discharge_summary_provider"))
    private Staff dischargingProvider;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_discharge_summary_assignment"))
    private UserRoleHospitalAssignment assignment;

    // Link to approval workflow if discharge required approval
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_record_id",
        foreignKey = @ForeignKey(name = "fk_discharge_summary_approval"))
    private DischargeApproval approvalRecord;

    @Column(name = "discharge_date", nullable = false)
    private LocalDate dischargeDate;

    @Column(name = "discharge_time")
    private LocalDateTime dischargeTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition", nullable = false, length = 50)
    private DischargeDisposition disposition;

    @NotBlank
    @Size(max = 5000)
    @Column(name = "discharge_diagnosis", nullable = false, length = 5000)
    private String dischargeDiagnosis;

    @Size(max = 5000)
    @Column(name = "hospital_course", length = 5000)
    private String hospitalCourse;

    @Size(max = 2000)
    @Column(name = "discharge_condition", length = 2000)
    private String dischargeCondition;

    // Structured discharge instructions
    @Size(max = 3000)
    @Column(name = "activity_restrictions", length = 3000)
    private String activityRestrictions;

    @Size(max = 3000)
    @Column(name = "diet_instructions", length = 3000)
    private String dietInstructions;

    @Size(max = 3000)
    @Column(name = "wound_care_instructions", length = 3000)
    private String woundCareInstructions;

    @Size(max = 3000)
    @Column(name = "follow_up_instructions", length = 3000)
    private String followUpInstructions;

    @Size(max = 2000)
    @Column(name = "warning_signs", length = 2000)
    private String warningSigns;

    @Size(max = 2000)
    @Column(name = "patient_education_provided", length = 2000)
    private String patientEducationProvided;

    // Medication reconciliation
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "discharge_medication_reconciliations",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "discharge_summary_id"),
        foreignKey = @ForeignKey(name = "fk_medication_reconciliation_discharge_summary")
    )
    @Builder.Default
    private List<MedicationReconciliationEntry> medicationReconciliation = new ArrayList<>();

    // Pending test results tracking
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "discharge_pending_test_results",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "discharge_summary_id"),
        foreignKey = @ForeignKey(name = "fk_pending_test_results_discharge_summary")
    )
    @Builder.Default
    private List<PendingTestResultEntry> pendingTestResults = new ArrayList<>();

    // Follow-up appointments scheduled
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "discharge_follow_up_appointments",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "discharge_summary_id"),
        foreignKey = @ForeignKey(name = "fk_follow_up_appointments_discharge_summary")
    )
    @OrderColumn(name = "appointment_order")
    @Builder.Default
    private List<FollowUpAppointmentEntry> followUpAppointments = new ArrayList<>();

    // Equipment/supplies needed at home
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "discharge_equipment_supplies",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "discharge_summary_id"),
        foreignKey = @ForeignKey(name = "fk_equipment_supplies_discharge_summary")
    )
    @OrderColumn(name = "equipment_order")
    @Builder.Default
    private List<String> equipmentAndSupplies = new ArrayList<>();

    @Column(name = "patient_or_caregiver_signature")
    private String patientOrCaregiverSignature;

    @Column(name = "signature_date_time")
    private LocalDateTime signatureDateTime;

    @Column(name = "provider_signature", length = 500)
    private String providerSignature;

    @Column(name = "provider_signature_date_time")
    private LocalDateTime providerSignatureDateTime;

    @Column(name = "is_finalized", nullable = false)
    @Builder.Default
    private Boolean isFinalized = false;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @Size(max = 2000)
    @Column(name = "additional_notes", length = 2000)
    private String additionalNotes;

    @Version
    private Long version;

    @PrePersist
    @PreUpdate
    private void validate() {
        if (Boolean.TRUE.equals(isFinalized) && finalizedAt == null) {
            finalizedAt = LocalDateTime.now();
        }
    }

    // Helper methods
    public void addMedicationReconciliation(MedicationReconciliationEntry entry) {
        if (medicationReconciliation == null) {
            medicationReconciliation = new ArrayList<>();
        }
        medicationReconciliation.add(entry);
    }

    public void addPendingTestResult(PendingTestResultEntry entry) {
        if (pendingTestResults == null) {
            pendingTestResults = new ArrayList<>();
        }
        pendingTestResults.add(entry);
    }

    public void addFollowUpAppointment(FollowUpAppointmentEntry entry) {
        if (followUpAppointments == null) {
            followUpAppointments = new ArrayList<>();
        }
        followUpAppointments.add(entry);
    }

    public void addEquipment(String equipment) {
        if (equipmentAndSupplies == null) {
            equipmentAndSupplies = new ArrayList<>();
        }
        equipmentAndSupplies.add(equipment);
    }

    public void finalizeSummary(String providerSignature) {
        this.isFinalized = true;
        this.finalizedAt = LocalDateTime.now();
        this.providerSignature = providerSignature;
        this.providerSignatureDateTime = LocalDateTime.now();
    }
}
