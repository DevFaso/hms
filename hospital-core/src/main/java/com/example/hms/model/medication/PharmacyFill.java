package com.example.hms.model.medication;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents a pharmacy fill/dispensing event from external pharmacy systems.
 * This captures actual medication dispensing data (retail, mail-order, or inpatient pharmacy)
 * separate from the prescription order itself.
 * 
 * Used for medication reconciliation, adherence tracking, and detecting medication overlaps.
 */
@Entity
@Table(
    name = "pharmacy_fills",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_pharmacy_fill_patient", columnList = "patient_id"),
        @Index(name = "idx_pharmacy_fill_prescription", columnList = "prescription_id"),
        @Index(name = "idx_pharmacy_fill_hospital", columnList = "hospital_id"),
        @Index(name = "idx_pharmacy_fill_date", columnList = "fill_date"),
        @Index(name = "idx_pharmacy_fill_ndc", columnList = "ndc_code"),
        @Index(name = "idx_pharmacy_fill_source", columnList = "source_system")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"patient", "hospital", "prescription"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PharmacyFill extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pharmacy_fill_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pharmacy_fill_hospital"))
    private Hospital hospital;

    /**
     * Optional link to the prescription that originated this fill.
     * May be null for pharmacy fills from external systems not linked to our prescriptions.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id",
        foreignKey = @ForeignKey(name = "fk_pharmacy_fill_prescription"))
    private Prescription prescription;

    // ===== Medication Identification =====

    @NotBlank
    @Size(max = 255)
    @Column(name = "medication_name", nullable = false, length = 255)
    private String medicationName;

    /**
     * NDC (National Drug Code) - 11-digit unique product identifier used in US.
     * Format: 5-4-2 (labeler-product-package) or variations like 4-4-2, 5-3-2.
     */
    @Size(max = 20)
    @Column(name = "ndc_code", length = 20)
    private String ndcCode;

    /**
     * RxNorm concept unique identifier (RxCUI) for semantic drug identification.
     */
    @Size(max = 20)
    @Column(name = "rxnorm_code", length = 20)
    private String rxnormCode;

    @Size(max = 100)
    @Column(name = "strength", length = 100)
    private String strength;

    @Size(max = 80)
    @Column(name = "dosage_form", length = 80)
    private String dosageForm; // tablet, capsule, solution, etc.

    // ===== Dispensing Details =====

    @NotNull
    @Column(name = "fill_date", nullable = false)
    private LocalDate fillDate;

    @Column(name = "quantity_dispensed", precision = 12, scale = 2)
    private BigDecimal quantityDispensed;

    @Size(max = 60)
    @Column(name = "quantity_unit", length = 60)
    private String quantityUnit; // tablets, mL, grams, etc.

    /**
     * Days supply indicates how many days the dispensed quantity should last
     * based on prescribed directions. Used for calculating when refill is due.
     */
    @Column(name = "days_supply")
    private Integer daysSupply;

    @Column(name = "refill_number")
    private Integer refillNumber; // 0 = initial fill, 1 = first refill, etc.

    @Size(max = 1000)
    @Column(name = "directions", length = 1000)
    private String directions; // SIG - directions for use

    // ===== Pharmacy Information =====

    @Size(max = 255)
    @Column(name = "pharmacy_name", length = 255)
    private String pharmacyName;

    @Size(max = 50)
    @Column(name = "pharmacy_npi", length = 50)
    private String pharmacyNpi; // National Provider Identifier

    @Size(max = 20)
    @Column(name = "pharmacy_ncpdp", length = 20)
    private String pharmacyNcpdp; // NCPDP - pharmacy identifier

    @Size(max = 120)
    @Column(name = "pharmacy_phone", length = 120)
    private String pharmacyPhone;

    @Size(max = 500)
    @Column(name = "pharmacy_address", length = 500)
    private String pharmacyAddress;

    // ===== Prescriber Information (from pharmacy record) =====

    @Size(max = 255)
    @Column(name = "prescriber_name", length = 255)
    private String prescriberName;

    @Size(max = 50)
    @Column(name = "prescriber_npi", length = 50)
    private String prescriberNpi;

    @Size(max = 50)
    @Column(name = "prescriber_dea", length = 50)
    private String prescriberDea; // DEA number for controlled substances

    // ===== Source and Integration =====

    /**
     * Identifies the external system that provided this fill record.
     * Examples: "SureScripts", "Surescripts", "CoverMyMeds", "Retail_Pharmacy_API", "MANUAL_ENTRY"
     */
    @Size(max = 100)
    @Column(name = "source_system", length = 100)
    private String sourceSystem;

    /**
     * External reference identifier from the source system (e.g., SureScripts message ID).
     */
    @Size(max = 255)
    @Column(name = "external_reference_id", length = 255)
    private String externalReferenceId;

    @Column(name = "is_controlled_substance")
    @Builder.Default
    private boolean controlledSubstance = false;

    @Column(name = "is_generic_substitution")
    @Builder.Default
    private boolean genericSubstitution = false;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;
}
