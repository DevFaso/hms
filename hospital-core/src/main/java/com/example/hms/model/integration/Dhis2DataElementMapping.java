package com.example.hms.model.integration;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Maps an HMS clinical concept (LOINC / ICD-10 / ATC / RxNorm / CVX /
 * HMS-local) to its DHIS2 dataElement UID — and optionally its
 * categoryOptionCombo UID — within a given dataset.
 *
 * <p>Per-hospital so that each ministry of health's own DHIS2 instance
 * can use its own dataElement vocabulary without forcing global
 * coordination across DevFaso deployments.
 */
@Entity
@Table(
    name = "dhis2_dataelement_mapping",
    schema = "integration",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_dhis2_mapping_per_dataset",
            columnNames = {"hospital_id", "hms_concept_system", "hms_concept_code", "dataset_uid"})
    },
    indexes = {
        @Index(name = "idx_dhis2_mapping_lookup",
            columnList = "hospital_id, dataset_uid, hms_concept_system")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "hospital")
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Dhis2DataElementMapping extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_dhis2_mapping_hospital"))
    private Hospital hospital;

    @NotBlank
    @Size(max = 255)
    @Column(name = "hms_concept_system", nullable = false, length = 255)
    private String hmsConceptSystem;

    @NotBlank
    @Size(max = 64)
    @Column(name = "hms_concept_code", nullable = false, length = 64)
    private String hmsConceptCode;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 dataElement UID must be 11 characters, alphanumeric, leading letter")
    @Column(name = "dhis2_dataelement_uid", nullable = false, length = 11)
    private String dhis2DataElementUid;

    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 categoryOptionCombo UID must be 11 characters, alphanumeric, leading letter")
    @Column(name = "dhis2_category_option_combo_uid", length = 11)
    private String dhis2CategoryOptionComboUid;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 16)
    private Dhis2PeriodType periodType;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 dataset UID must be 11 characters, alphanumeric, leading letter")
    @Column(name = "dataset_uid", nullable = false, length = 11)
    private String datasetUid;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
