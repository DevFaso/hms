package com.example.hms.model.pharmacy;

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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Normalized medication catalog item linked to a hospital's formulary.
 * Supports Burkina essential-medicines list with optional RxNorm crosswalk.
 */
@Entity
@Table(
    name = "medication_catalog_items",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_mci_hospital", columnList = "hospital_id"),
        @Index(name = "idx_mci_atc_code", columnList = "atc_code"),
        @Index(name = "idx_mci_name_fr", columnList = "name_fr"),
        @Index(name = "idx_mci_active", columnList = "active")
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

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_mci_hospital"))
    private Hospital hospital;

    @NotBlank
    @Size(max = 30)
    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @NotBlank
    @Size(max = 255)
    @Column(name = "name_fr", nullable = false, length = 255)
    private String nameFr;

    @Size(max = 255)
    @Column(name = "generic_name", length = 255)
    private String genericName;

    @Size(max = 10)
    @Column(name = "atc_code", length = 10)
    private String atcCode;

    @Size(max = 80)
    @Column(name = "form", length = 80)
    private String form;

    @Size(max = 100)
    @Column(name = "strength", length = 100)
    private String strength;

    @Size(max = 60)
    @Column(name = "unit", length = 60)
    private String unit;

    @Size(max = 20)
    @Column(name = "rxnorm_code", length = 20)
    private String rxnormCode;

    @Size(max = 1000)
    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
