package com.example.hms.model.medication;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Normalized medication catalog item linked to Burkina's essential-medicines list.
 * Replaces free-text medication names with structured, searchable medication data.
 */
@Entity
@Table(
    name = "medication_catalog_items",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_med_catalog_name_fr", columnList = "name_fr"),
        @Index(name = "idx_med_catalog_generic", columnList = "generic_name"),
        @Index(name = "idx_med_catalog_atc", columnList = "atc_code"),
        @Index(name = "idx_med_catalog_hospital", columnList = "hospital_id"),
        @Index(name = "idx_med_catalog_active", columnList = "active")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"hospital"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class MedicationCatalogItem extends BaseEntity {

    /** Internal code (formulary code). */
    @Size(max = 30)
    @Column(name = "code", length = 30)
    private String code;

    /** French name (primary display name). */
    @NotBlank
    @Size(max = 500)
    @Column(name = "name_fr", nullable = false, length = 500)
    private String nameFr;

    /** International Nonproprietary Name (INN) / generic name. */
    @NotBlank
    @Size(max = 500)
    @Column(name = "generic_name", nullable = false, length = 500)
    private String genericName;

    @Size(max = 500)
    @Column(name = "brand_name", length = 500)
    private String brandName;

    /** ATC (Anatomical Therapeutic Chemical) classification code. */
    @Size(max = 20)
    @Column(name = "atc_code", length = 20)
    private String atcCode;

    /** Dosage form (tablet, capsule, syrup, injection, etc.). */
    @Size(max = 100)
    @Column(length = 100)
    private String form;

    /** Strength value (e.g. "500", "250/125"). */
    @Size(max = 100)
    @Column(length = 100)
    private String strength;

    /** Strength unit (mg, ml, g, etc.). */
    @Size(max = 50)
    @Column(name = "strength_unit", length = 50)
    private String strengthUnit;

    /** Optional RxNorm crosswalk code. */
    @Size(max = 20)
    @Column(name = "rxnorm_code", length = 20)
    private String rxnormCode;

    /**
     * Optional ISMP "tall-man" lettering for confusable drug pairs
     * (e.g. {@code "predniSONE"} vs {@code "prednisoLONE"}). When present,
     * the medication-prescribe CDS hook service surfaces this in card
     * detail to disambiguate at authoring time. Added in V93.
     */
    @Size(max = 200)
    @Column(name = "tall_man_name", length = 200)
    private String tallManName;

    /** Route of administration (oral, IV, IM, topical, etc.). */
    @Size(max = 100)
    @Column(length = 100)
    private String route;

    /** Therapeutic category (antibiotic, analgesic, etc.). */
    @Size(max = 100)
    @Column(length = 100)
    private String category;

    /**
     * Pediatric weight-based dose ceiling consulted by the
     * {@code PediatricDoseRule} (see V63). Optional — when null, the rule
     * emits an advisory instead of a hard block.
     */
    @Column(name = "pediatric_max_dose_mg_per_kg", precision = 8, scale = 3)
    private java.math.BigDecimal pediatricMaxDoseMgPerKg;

    /** Whether this is on Burkina's essential medicines list. */
    @Column(name = "essential_list", nullable = false)
    @Builder.Default
    private boolean essentialList = false;

    /** Whether this is a controlled substance requiring dual-approval. */
    @Column(nullable = false)
    @Builder.Default
    private boolean controlled = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Owning hospital. Optional: {@code null} marks the entry as a
     * <strong>platform / global catalog entry</strong> visible to every
     * tenant — the intended representation of items on Burkina Faso's
     * Liste Nationale des Médicaments Essentiels (LNME). A non-null
     * hospital marks the entry as hospital-specific (an additional SKU
     * or local substitution that should not surface to other tenants).
     *
     * <p>Uniqueness is enforced at the DB level (migration V95) via two
     * partial unique indexes: one for global rows ({@code hospital_id
     * IS NULL}) on {@code code} alone, one for hospital-scoped rows on
     * {@code (hospital_id, code)}. The two scopes never shadow each
     * other.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id",
        foreignKey = @ForeignKey(name = "fk_med_catalog_hospital"))
    private Hospital hospital;
}
