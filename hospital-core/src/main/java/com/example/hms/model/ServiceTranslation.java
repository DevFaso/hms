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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;

@Entity
@Table(
    name = "service_translations",
    schema = "support",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_service_translation_treatment_lang",
            columnNames = {"treatment_id", "language_code"})
    },
    indexes = {
        @Index(name = "idx_st_treatment", columnList = "treatment_id"),
        @Index(name = "idx_st_assignment", columnList = "assignment_id"),
        @Index(name = "idx_st_language_code", columnList = "language_code")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"treatment", "assignment"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ServiceTranslation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "treatment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_st_treatment"))
    private Treatment treatment;

    @NotBlank
    @Size(max = 10) // allow BCP47 like "pt-BR" but keep it tight
    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String name;

    @Size(max = 1000)
    @Column(length = 1000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_st_assignment"))
    private UserRoleHospitalAssignment assignment;

    @PrePersist
    @PreUpdate
    void normalizeAndValidate() {
        if (languageCode != null) languageCode = languageCode.trim().toLowerCase();
        // Assignment hospital must match treatment hospital
        if (treatment == null || assignment == null || assignment.getHospital() == null
            || treatment.getHospital() == null
            || !assignment.getHospital().getId().equals(treatment.getHospital().getId())) {
            throw new IllegalStateException("ServiceTranslation.assignment.hospital must match treatment.hospital");
        }
    }
}
