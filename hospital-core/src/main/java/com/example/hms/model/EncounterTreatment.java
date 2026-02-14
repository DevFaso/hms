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
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
    name = "encounter_treatments",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_et_encounter", columnList = "encounter_id"),
        @Index(name = "idx_et_treatment", columnList = "treatment_id"),
        @Index(name = "idx_et_staff", columnList = "staff_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class EncounterTreatment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_et_encounter"))
    private Encounter encounter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "treatment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_et_treatment"))
    private Treatment treatment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id",
        foreignKey = @ForeignKey(name = "fk_et_staff"))
    private Staff staff;

    @NotNull
    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    @Column(length = 500)
    private String outcome;

    @Column(length = 1000)
    private String notes;

    @PrePersist
    @PreUpdate
    private void validate() {
        if (performedAt == null) performedAt = LocalDateTime.now();

        // Treatment hospital must match encounter hospital
        if (treatment == null || treatment.getHospital() == null
            || encounter == null || encounter.getHospital() == null
            || !Objects.equals(treatment.getHospital().getId(), encounter.getHospital().getId())) {
            throw new IllegalStateException("EncounterTreatment.treatment.hospital must match encounter.hospital");
        }

        // If staff provided, staff hospital must match encounter hospital
        if (staff != null && staff.getHospital() != null
            && !Objects.equals(staff.getHospital().getId(), encounter.getHospital().getId())) {
            throw new IllegalStateException("EncounterTreatment.staff must belong to encounter.hospital");
        }
    }
}
