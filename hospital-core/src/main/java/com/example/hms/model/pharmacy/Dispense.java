package com.example.hms.model.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.DispenseVerificationStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.Prescription;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Records each dispensing event from a hospital dispensary.
 * Tracks medication, quantity, substitution, and verification.
 */
@Entity
@Table(
    name = "dispenses",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_disp_prescription", columnList = "prescription_id"),
        @Index(name = "idx_disp_patient", columnList = "patient_id"),
        @Index(name = "idx_disp_pharmacy", columnList = "pharmacy_id"),
        @Index(name = "idx_disp_status", columnList = "status"),
        @Index(name = "idx_disp_dispensed_at", columnList = "dispensed_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"prescription", "patient", "pharmacy", "stockLot",
    "dispensedByUser", "verifiedByUser", "medicationCatalogItem"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Dispense extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescription_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_disp_prescription"))
    private Prescription prescription;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_disp_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pharmacy_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_disp_pharmacy"))
    private Pharmacy pharmacy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_lot_id",
        foreignKey = @ForeignKey(name = "fk_disp_lot"))
    private StockLot stockLot;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispensed_by", nullable = false,
        foreignKey = @ForeignKey(name = "fk_disp_dispensed_by"))
    private User dispensedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by",
        foreignKey = @ForeignKey(name = "fk_disp_verified_by"))
    private User verifiedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medication_catalog_id",
        foreignKey = @ForeignKey(name = "fk_disp_medication"))
    private MedicationCatalogItem medicationCatalogItem;

    @NotBlank
    @Size(max = 255)
    @Column(name = "medication_name", nullable = false, length = 255)
    private String medicationName;

    @NotNull
    @Column(name = "quantity_requested", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantityRequested;

    @NotNull
    @Column(name = "quantity_dispensed", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantityDispensed;

    @Size(max = 60)
    @Column(name = "unit", length = 60)
    private String unit;

    @Column(name = "substitution", nullable = false)
    @Builder.Default
    private boolean substitution = false;

    @Size(max = 500)
    @Column(name = "substitution_reason", length = 500)
    private String substitutionReason;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private DispenseStatus status = DispenseStatus.COMPLETED;

    @Size(max = 1000)
    @Column(name = "notes", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String notes;

    @NotNull
    @Column(name = "dispensed_at", nullable = false)
    @Builder.Default
    private LocalDateTime dispensedAt = LocalDateTime.now();

    /**
     * Roadmap row 4 / T-68 — optional client-supplied idempotency key for
     * POST /pharmacy/dispense replay from the offline queue.
     *
     * <p>Generation contract (frontend):
     * {@code <userId>-<prescriptionId>-<isoTimestampWithNanos>} — stable
     * across retries of the same logical action, distinct across two
     * deliberate dispenses of the same prescription seconds apart.
     *
     * <p>Resolution contract (DispenseServiceImpl.createDispense):
     * if a row with this key already exists, the second POST returns the
     * existing record and skips every side-effect (stock decrement, audit,
     * SMS). Default {@code null} preserves today's behaviour for callers
     * that do not opt in.
     *
     * <p>Backed by V94 + a partial UNIQUE index ({@code uq_disp_idempotency_key})
     * so concurrent replays collide at the DB layer.
     */
    @Size(max = 64)
    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    /* ── Counter-side verification (Tier 2 item 34) ─────────────────────── */
    //
    // Mirrors the bedside five-rights columns on
    // MedicationAdministrationRecord, so the two steps of one medication
    // chain read the same way to anyone auditing them.

    /** Raw value scanned from the patient's wristband (the bare patient UUID). */
    @Size(max = 255)
    @Column(name = "patient_scan_value", length = 255)
    private String patientScanValue;

    /** Raw value scanned from the stock lot's printed label. */
    @Size(max = 255)
    @Column(name = "product_scan_value", length = 255)
    private String productScanValue;

    /** When the scan was performed. Null on the paper-fallback path. */
    @Column(name = "scan_verified_at")
    private LocalDateTime scanVerifiedAt;

    /**
     * NOT_VERIFIED is the honest default, not a failure: most sites in this
     * deployment have no scanner, and the server-side expiry and drug-match
     * checks run regardless of what this says.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    @Builder.Default
    private DispenseVerificationStatus verificationStatus = DispenseVerificationStatus.NOT_VERIFIED;

    /**
     * JSON array of {@link com.example.hms.enums.DispenseCheck} names that
     * failed and were overridden. Null unless the status is OVERRIDDEN.
     */
    @Column(name = "verification_overrides", columnDefinition = "JSONB")
    private String verificationOverrides;

    /** Why the pharmacist proceeded past a failed check. */
    @Size(max = 1024)
    @Column(name = "verification_override_reason", length = 1024)
    private String verificationOverrideReason;
}
