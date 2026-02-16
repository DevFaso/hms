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
    name = "department_translations",
    schema = "hospital",
    uniqueConstraints = {
        // one translation per department per language
        @UniqueConstraint(name = "uq_dept_translation_dept_lang", columnNames = {"department_id", "language_code"})
    },
    indexes = {
        @Index(name = "idx_dept_translation_dept", columnList = "department_id"),
        @Index(name = "idx_dept_translation_lang", columnList = "language_code"),
        @Index(name = "idx_dept_translation_assignment", columnList = "assignment_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"department", "assignment"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class DepartmentTranslation extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_dept_translation_assignment"))
    private UserRoleHospitalAssignment assignment;

    // Use a single, canonical language field (BCP47 like en, fr, pt-BR)
    @NotBlank
    @Size(max = 10)
    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_dept_translation_department"))
    private Department department;

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String name;

    @Size(max = 2048)
    @Column(name = "description", length = 2048)
    private String description;

    @Size(max = 100)
    @Column(name = "language", length = 100)
    private String language;

    @PrePersist
    @PreUpdate
    void normalizeAndValidate() {
        if (languageCode != null) languageCode = languageCode.trim().toLowerCase();
        // assignment’s hospital must match department’s hospital
        if (assignment == null || assignment.getHospital() == null
            || department == null || department.getHospital() == null
            || !assignment.getHospital().getId().equals(department.getHospital().getId())) {
            throw new IllegalStateException("DepartmentTranslation.assignment.hospital must match department.hospital");
        }
    }

}
