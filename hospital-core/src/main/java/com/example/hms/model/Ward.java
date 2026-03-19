package com.example.hms.model;

import com.example.hms.enums.WardType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
    name = "wards",
    schema = "hospital",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_ward_hospital_code", columnNames = {"hospital_id", "code"})
    },
    indexes = {
        @Index(name = "idx_ward_hospital", columnList = "hospital_id"),
        @Index(name = "idx_ward_department", columnList = "department_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"hospital", "department", "beds"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Ward extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_ward_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id",
        foreignKey = @ForeignKey(name = "fk_ward_department"))
    private Department department;

    @NotBlank
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @NotBlank
    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "ward_type", nullable = false, length = 30)
    private WardType wardType;

    @Column(name = "floor")
    private Integer floor;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @OneToMany(mappedBy = "ward", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<Bed> beds = new HashSet<>();
}
