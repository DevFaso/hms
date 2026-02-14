package com.example.hms.model;

import com.example.hms.model.converter.LabTestReferenceRangeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(
    name = "lab_test_definitions",
    schema = "lab",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_lab_testdef_name_global", columnNames = {"name"}),
        @UniqueConstraint(name = "uq_lab_testdef_code_global", columnNames = {"test_code"})
    },
    indexes = {
        @Index(name = "idx_lab_testdef_hospital", columnList = "hospital_id"),
        @Index(name = "idx_lab_testdef_name", columnList = "name"),
        @Index(name = "idx_lab_testdef_assignment", columnList = "assignment_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, of = "testCode")
@ToString(exclude = {"labOrders", "assignment", "hospital"})
public class LabTestDefinition extends BaseEntity {

    @NotBlank
    @Size(max = 50)
    @Column(name = "test_code", nullable = false, length = 50)
    private String testCode;

    @NotBlank
    @Size(max = 255)
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Size(max = 100)
    @Column(name = "category", length = 100)
    private String category;

    @Size(max = 2048)
    @Column(name = "description", length = 2048)
    private String description;

    @Size(max = 100)
    @Column(name = "sample_type", length = 100)
    private String sampleType;

    @Size(max = 50)
    @Column(length = 50)
    private String unit;

    @Size(max = 1000)
    @Column(name = "preparation_instructions", length = 1000)
    private String preparationInstructions;

    @Column(name = "turnaround_time_minutes")
    private Integer turnaroundTimeMinutes;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Convert(converter = LabTestReferenceRangeConverter.class)
    @Column(name = "reference_ranges", columnDefinition = "TEXT")
    @Builder.Default
    private List<LabTestReferenceRange> referenceRanges = new ArrayList<>();


    @OneToMany(mappedBy = "labTestDefinition", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<LabOrder> labOrders = new HashSet<>();

    /** Who created/owns this definition (role@hospital). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id",
        foreignKey = @ForeignKey(name = "fk_labtestdef_assignment"))
    private UserRoleHospitalAssignment assignment;

    /** Explicit hospital for fast lookups and uniqueness; optional for global definitions. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id",
        foreignKey = @ForeignKey(name = "fk_labtestdef_hospital"))
    private Hospital hospital;

    @PrePersist
    @PreUpdate
    private void validate() {
        // Normalize key fields for consistency
        if (testCode != null) testCode = testCode.trim().toUpperCase();
        if (name != null) name = name.trim();
        if (category != null) category = category.trim().toUpperCase();
        if (sampleType != null) sampleType = sampleType.trim().toUpperCase();

        // Lab test definitions are nationally scoped, never tied to a single hospital
        hospital = null;

        if (referenceRanges == null) {
            referenceRanges = new ArrayList<>();
        }
    }
}
