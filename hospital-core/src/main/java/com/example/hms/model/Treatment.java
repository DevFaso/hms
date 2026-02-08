package com.example.hms.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
    name = "treatments",
    schema = "clinical",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_treatment_hospital_name", columnNames = {"hospital_id", "name"})
    },
    indexes = {
        @Index(name = "idx_treatment_hospital", columnList = "hospital_id"),
        @Index(name = "idx_treatment_department", columnList = "department_id"),
        @Index(name = "idx_treatment_assignment", columnList = "assignment_id"),
        @Index(name = "idx_treatment_active", columnList = "active")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"department", "hospital", "translations", "encounterTreatments", "assignment"})
@SQLDelete(sql = "UPDATE clinical.treatments SET active = false WHERE id = ?")
@SQLRestriction(value = "active = true")
@NamedEntityGraph(
    name = "Treatment.withBasics",
    attributeNodes = {
        @NamedAttributeNode("department"),
        @NamedAttributeNode("hospital"),
        @NamedAttributeNode("assignment")
    }
)
public class Treatment extends BaseEntity {

    @NotBlank
    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 1000)
    private String description;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_treatment_department"))
    private Department department;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_treatment_hospital"))
    private Hospital hospital;

    @NotNull
    @DecimalMin(value = "0.00")
    @Digits(integer = 8, fraction = 2)
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Positive
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @NotNull
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_treatment_assignment"))
    @OnDelete(action = OnDeleteAction.RESTRICT)
    private UserRoleHospitalAssignment assignment;

    @OneToMany(mappedBy = "treatment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<ServiceTranslation> translations = new HashSet<>();

    @OneToMany(mappedBy = "treatment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<EncounterTreatment> encounterTreatments = new HashSet<>();

    @PrePersist
    void prePersist() {
        if (active == null) active = true;
    }
}
