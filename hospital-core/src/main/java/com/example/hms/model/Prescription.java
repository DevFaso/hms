package com.example.hms.model;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.prescription.PrescriptionAlert;
import com.example.hms.model.prescription.PrescriptionInstruction;
import com.example.hms.model.prescription.PrescriptionTransmission;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(
    name = "prescriptions",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_rx_patient", columnList = "patient_id"),
        @Index(name = "idx_rx_staff", columnList = "staff_id"),
        @Index(name = "idx_rx_encounter", columnList = "encounter_id"),
        @Index(name = "idx_rx_assignment", columnList = "assignment_id"),
        // NOTE: BaseEntity column is likely "createdAt" (camelCase). Use that, not "created_at".
        @Index(name = "idx_rx_createdAt", columnList = "createdAt"),
        @Index(name = "idx_rx_hospital", columnList = "hospital_id"),
        @Index(name = "idx_rx_status", columnList = "status"),
        @Index(name = "idx_rx_dispatch_status", columnList = "dispatch_status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"patient", "staff", "encounter", "assignment", "hospital", "structuredInstructions", "alerts", "transmissions"})
public class Prescription extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_rx_patient"))
    private Patient patient;

    /** Prescriber */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_rx_staff"))
    private Staff staff;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_rx_encounter"))
    private Encounter encounter;

    /** Prescriber's hospital context (fast filter & integrity checks). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_rx_hospital"))
    private Hospital hospital;

    // REQUIRED: link to the prescriber's hospital-scoped assignment
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_rx_assignment"))
    private UserRoleHospitalAssignment assignment;

    @NotBlank
    @Size(max = 255)
    @Column(name = "medication_name", nullable = false, length = 255)
    private String medicationName;

    @Size(max = 64)
    @Column(name = "medication_code", length = 64)
    private String medicationCode;

    @Size(max = 255)
    @Column(name = "medication_display_name", length = 255)
    private String medicationDisplayName;

    @Size(max = 100)
    @Column(name = "dosage", length = 100)
    private String dosage;

    @Size(max = 40)
    @Column(name = "dose_unit", length = 40)
    private String doseUnit;

    @Size(max = 80)
    @Column(name = "route", length = 80)
    private String route;

    @Size(max = 100)
    @Column(name = "frequency", length = 100)
    private String frequency;

    @Size(max = 100)
    @Column(name = "duration", length = 100)
    private String duration;

    @Column(name = "quantity", precision = 12, scale = 2)
    private BigDecimal quantity;

    @Size(max = 60)
    @Column(name = "quantity_unit", length = 60)
    private String quantityUnit;

    @Column(name = "refills_allowed")
    private Integer refillsAllowed;

    @Column(name = "refills_remaining")
    private Integer refillsRemaining;

    @Size(max = 2048)
    @Column(name = "instructions", length = 2048)
    private String instructions;

    @Column(name = "patient_instruction_json", columnDefinition = "JSONB")
    private String patientInstructionJson;

    @Column(name = "education_material_json", columnDefinition = "JSONB")
    private String educationMaterialJson;

    @Column(name = "pharmacy_id")
    private java.util.UUID pharmacyId;

    @Size(max = 255)
    @Column(name = "pharmacy_name", length = 255)
    private String pharmacyName;

    @Size(max = 50)
    @Column(name = "pharmacy_npi", length = 50)
    private String pharmacyNpi;

    @Size(max = 120)
    @Column(name = "pharmacy_contact", length = 120)
    private String pharmacyContact;

    @Size(max = 255)
    @Column(name = "pharmacy_address", length = 255)
    private String pharmacyAddress;

    @Size(max = 40)
    @Column(name = "dispatch_channel", length = 40)
    private String dispatchChannel;

    @Size(max = 40)
    @Column(name = "dispatch_status", length = 40)
    private String dispatchStatus;

    @Size(max = 120)
    @Column(name = "dispatch_reference", length = 120)
    private String dispatchReference;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Builder.Default
    @Column(name = "controlled_substance", nullable = false)
    private boolean controlledSubstance = false;

    @Builder.Default
    @Column(name = "inpatient_order", nullable = false)
    private boolean inpatientOrder = false;

    @Builder.Default
    @Column(name = "allergies_reviewed", nullable = false)
    private boolean allergiesReviewed = false;

    @Builder.Default
    @Column(name = "interactions_reviewed", nullable = false)
    private boolean interactionsReviewed = false;

    @Builder.Default
    @Column(name = "contraindications_reviewed", nullable = false)
    private boolean contraindicationsReviewed = false;

    @Size(max = 1024)
    @Column(name = "override_reason", length = 1024)
    private String overrideReason;

    @Size(max = 40)
    @Column(name = "two_factor_method", length = 40)
    private String twoFactorMethod;

    @Size(max = 120)
    @Column(name = "two_factor_reference", length = 120)
    private String twoFactorReference;

    @Column(name = "two_factor_verified_at")
    private LocalDateTime twoFactorVerifiedAt;

    @Builder.Default
    @Column(name = "requires_cosign", nullable = false)
    private boolean requiresCosign = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cosigned_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_rx_cosign_staff"))
    private Staff cosignedBy;

    @Column(name = "cosigned_at")
    private LocalDateTime cosignedAt;

    @Size(max = 1024)
    @Column(name = "notes", length = 1024)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    @Builder.Default
    private PrescriptionStatus status = PrescriptionStatus.DRAFT;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "prescription_instructions",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "prescription_id",
            foreignKey = @ForeignKey(name = "fk_prescription_instruction_rx"))
    )
    @OrderColumn(name = "instruction_order")
    private List<PrescriptionInstruction> structuredInstructions = new ArrayList<>();

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "prescription_alerts",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "prescription_id",
            foreignKey = @ForeignKey(name = "fk_prescription_alert_rx"))
    )
    @OrderColumn(name = "alert_order")
    private List<PrescriptionAlert> alerts = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "prescription", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PrescriptionTransmission> transmissions = new ArrayList<>();

    @Version
    private Long version;

    public void addTransmission(PrescriptionTransmission transmission) {
        if (transmission == null) {
            return;
        }
        transmissions.add(transmission);
        transmission.setPrescription(this);
    }

    @PrePersist
    @PreUpdate
    private void validate() {
        normalizeQuantities();
        normalizeStrings();
        validateContextIntegrity();
    }

    private void normalizeQuantities() {
        if (refillsAllowed != null && (refillsRemaining == null || refillsRemaining > refillsAllowed)) {
            refillsRemaining = refillsAllowed;
        }
    }

    private void normalizeStrings() {
        if (medicationName != null) medicationName = medicationName.trim();
        if (medicationDisplayName != null) medicationDisplayName = medicationDisplayName.trim();
        if (dosage != null) dosage = dosage.trim();
        if (frequency != null) frequency = frequency.trim();
        if (duration != null) duration = duration.trim();
        if (overrideReason != null) overrideReason = overrideReason.trim();
        if (dispatchChannel != null) dispatchChannel = dispatchChannel.trim();
        if (dispatchStatus != null) dispatchStatus = dispatchStatus.trim();
        if (twoFactorMethod != null) twoFactorMethod = twoFactorMethod.trim();
        if (twoFactorReference != null) twoFactorReference = twoFactorReference.trim();
    }

    private void validateContextIntegrity() {
        if (hospital == null || staff == null || staff.getHospital() == null
            || !Objects.equals(staff.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Staff.hospital must match Prescription.hospital");
        }
        if (assignment == null || assignment.getHospital() == null
            || !Objects.equals(assignment.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Assignment.hospital must match Prescription.hospital");
        }
        if (encounter == null || encounter.getHospital() == null
            || !Objects.equals(encounter.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Encounter.hospital must match Prescription.hospital");
        }
        if (!Objects.equals(encounter.getPatient().getId(), patient.getId())) {
            throw new IllegalStateException("Encounter.patient must match Prescription.patient");
        }
        if (!Objects.equals(encounter.getStaff().getId(), staff.getId())) {
            throw new IllegalStateException("Encounter.staff must match Prescription.staff");
        }
    }
}
