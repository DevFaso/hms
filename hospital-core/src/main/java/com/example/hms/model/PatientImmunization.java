package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Entity representing a patient's immunization record.
 * Tracks vaccines administered, due dates, and immunization history.
 */
@Entity
@Table(
    name = "patient_immunization",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_immunization_patient", columnList = "patient_id"),
        @Index(name = "idx_immunization_hospital", columnList = "hospital_id"),
        @Index(name = "idx_immunization_vaccine", columnList = "vaccine_code"),
        @Index(name = "idx_immunization_date", columnList = "administration_date"),
        @Index(name = "idx_immunization_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PatientImmunization extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_immunization_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_immunization_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "administered_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_immunization_staff"))
    private Staff administeredBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id",
        foreignKey = @ForeignKey(name = "fk_immunization_encounter"))
    private Encounter encounter; // Optional: link to encounter if given during visit

    // === Vaccine Information ===
    @Column(name = "vaccine_code", length = 50)
    private String vaccineCode; // CVX code (CDC vaccine code)

    @Column(name = "vaccine_display", length = 255, nullable = false)
    private String vaccineDisplay; // Human-readable vaccine name (e.g., "COVID-19, mRNA, LNP-S, PF, 30 mcg/0.3 mL dose")

    @Column(name = "vaccine_type", length = 100)
    private String vaccineType; // Influenza, COVID-19, Tdap, MMR, Hepatitis B, etc.

    @Column(name = "target_disease", length = 255)
    private String targetDisease; // Disease(s) vaccine prevents

    // === Administration Details ===
    @Column(name = "administration_date", nullable = false)
    private LocalDate administrationDate;

    @Column(name = "dose_number")
    private Integer doseNumber; // 1, 2, 3, etc. for multi-dose series

    @Column(name = "total_doses_in_series")
    private Integer totalDosesInSeries; // Expected total doses for this vaccine

    @Column(name = "dose_quantity")
    private Double doseQuantity; // Amount administered

    @Column(name = "dose_unit", length = 50)
    private String doseUnit; // mL, mcg, units

    @Column(name = "route", length = 50)
    private String route; // IM (intramuscular), SC (subcutaneous), PO (oral), intranasal

    @Column(name = "site", length = 100)
    private String site; // Left deltoid, right deltoid, left thigh, etc.

    // === Vaccine Product Details ===
    @Column(name = "manufacturer", length = 200)
    private String manufacturer; // Pfizer, Moderna, J&J, etc.

    @Column(name = "lot_number", length = 100)
    private String lotNumber; // Vaccine lot/batch number

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Column(name = "ndc_code", length = 20)
    private String ndcCode; // National Drug Code

    // === Status & Verification ===
    @Column(name = "status", length = 50, nullable = false)
    private String status; // COMPLETED, NOT_DONE, REFUSED, DEFERRED, ENTERED_IN_ERROR

    @Column(name = "status_reason", length = 500)
    private String statusReason; // Why not given (patient refusal, contraindication, etc.)

    @Column(name = "verified")
    private Boolean verified; // Verified by provider vs patient-reported

    @Column(name = "source_of_record", length = 100)
    private String sourceOfRecord; // Given here, patient self-report, external records, immunization registry

    // === Reaction & Safety ===
    @Column(name = "adverse_reaction")
    private Boolean adverseReaction;

    @Column(name = "reaction_description", length = 1000)
    private String reactionDescription; // Local swelling, fever, allergic reaction, etc.

    @Column(name = "reaction_severity", length = 50)
    private String reactionSeverity; // Mild, moderate, severe

    @Column(name = "contraindication")
    private Boolean contraindication; // Known contraindication for this vaccine

    @Column(name = "contraindication_reason", length = 500)
    private String contraindicationReason;

    // === Scheduling & Reminders ===
    @Column(name = "next_dose_due_date")
    private LocalDate nextDoseDueDate; // When next dose in series is due

    @Column(name = "reminder_sent")
    private Boolean reminderSent;

    @Column(name = "reminder_sent_date")
    private LocalDate reminderSentDate;

    @Column(name = "overdue")
    private Boolean overdue; // Calculated field: past due date

    // === Clinical Significance ===
    @Column(name = "required_for_school")
    private Boolean requiredForSchool; // Required vaccination for school/daycare

    @Column(name = "required_for_travel")
    private Boolean requiredForTravel; // Required for international travel

    @Column(name = "occupational_requirement")
    private Boolean occupationalRequirement; // Required for healthcare workers, etc.

    @Column(name = "pregnancy_related")
    private Boolean pregnancyRelated; // Given during pregnancy (Tdap, flu, etc.)

    // === Documentation ===
    @Column(name = "vis_given")
    private Boolean visGiven; // Vaccine Information Statement provided to patient

    @Column(name = "vis_date")
    private LocalDate visDate; // Date of VIS document

    @Column(name = "consent_obtained")
    private Boolean consentObtained;

    @Column(name = "consent_date")
    private LocalDate consentDate;

    @Column(name = "insurance_reported")
    private Boolean insuranceReported; // Reported to insurance for billing

    @Column(name = "registry_reported")
    private Boolean registryReported; // Reported to state/national immunization registry

    @Column(name = "registry_reported_date")
    private LocalDate registryReportedDate;

    // === Additional Notes ===
    @Column(name = "notes", length = 2048)
    private String notes;

    @Column(name = "external_reference_id", length = 100)
    private String externalReferenceId; // Reference to external immunization registry

    @Builder.Default
    @Column(name = "active")
    private Boolean active = true; // Current vs entered in error

    @PrePersist
    @PreUpdate
    protected void updateOverdueStatus() {
        if (this.nextDoseDueDate != null) {
            this.overdue = LocalDate.now().isAfter(this.nextDoseDueDate);
        }
    }
}
