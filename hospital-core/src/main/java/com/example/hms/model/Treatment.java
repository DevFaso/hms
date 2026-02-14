package com.example.hms.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
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
@EqualsAndHashCode(callSuper = true)
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
