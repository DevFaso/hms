package com.example.hms.model;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.model.converter.LabTestReferenceRangeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
    name = "lab_test_definitions",
    schema = "lab",
    // QA D1120: no unique key on the code or the name. The database never had
    // one (these were schema-generation-only and hid the duplicate in tests);
    // versions of one test share both. The service refuses a second live
    // definition with a code (requireCodeFree) and links versions instead.
    indexes = {
        @Index(name = "idx_lab_testdef_hospital", columnList = "hospital_id"),
        @Index(name = "idx_lab_testdef_name", columnList = "name"),
        @Index(name = "idx_lab_testdef_assignment", columnList = "assignment_id"),
        @Index(name = "idx_lab_testdef_loinc", columnList = "loinc_code")
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

    /**
     * LOINC code identifying this lab observation in FHIR {@code Observation.code}.
     * Format is enforced at the service layer via
     * {@code TerminologyCodes#isValidLoinc} so freetext definitions remain
     * importable until they are re-coded.
     */
    @Size(max = 20)
    @Column(name = "loinc_code", length = 20)
    private String loincCode;

    @Size(max = 255)
    @Column(name = "loinc_display", length = 255)
    private String loincDisplay;

    @Size(max = 1000)
    @Column(name = "preparation_instructions", length = 1000)
    private String preparationInstructions;

    @Column(name = "turnaround_time_minutes")
    private Integer turnaroundTimeMinutes;

    /**
     * QA D834: the expected (normal) answer of a qualitative test ("Négatif").
     * A qualitative test has no numeric reference range; it goes through review
     * and activation with this instead, and a result that reads the same is
     * graded normal.
     */
    @Size(max = 255)
    @Column(name = "expected_result", length = 255)
    private String expectedResult;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * True when this test is performed at the bedside rather than in the
     * laboratory — glucometer, urine dipstick, rapid malaria, haemoglobin.
     *
     * <p>Governs who may enter a result for it. Nurses and midwives may enter
     * point-of-care results, because they are the ones who ran the test;
     * laboratory staff may enter any. Before this existed, a nurse could
     * create, edit and release a result for a chemistry panel they had no
     * part in.
     *
     * <p>Never seeded. Which tests are point-of-care varies by site, by the
     * devices on hand and by local protocol, so each hospital's laboratory
     * marks its own. Defaults false, which fails closed on an authority
     * question.
     */
    @Column(name = "point_of_care", nullable = false)
    @Builder.Default
    private boolean pointOfCare = false;

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

    // ── Approval workflow ──────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 40)
    @Builder.Default
    private LabTestDefinitionApprovalStatus approvalStatus = LabTestDefinitionApprovalStatus.DRAFT;

    /** UUID of the Lab Director who approved or rejected this definition. */
    @Column(name = "approved_by_id")
    private UUID approvedById;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** QA D213: who submitted the definition for QA review. */
    @Column(name = "submitted_by_id")
    private UUID submittedById;

    /** UUID of the Quality Manager who completed QA review. */
    @Column(name = "reviewed_by_id")
    private UUID reviewedById;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "rejection_reason", length = 2048)
    private String rejectionReason;

    /**
     * QA D1120: the ACTIVE definition this version replaces once it is
     * activated (null for a first version). Versions share the test code.
     */
    @Column(name = "supersedes_definition_id")
    private UUID supersedesDefinitionId;

    /** QA D1120: 1 for a first definition, the replaced version's plus one for a new version. */
    @Column(name = "version_number", nullable = false)
    @Builder.Default
    private Integer versionNumber = 1;

    /**
     * QA D834: a qualitative test states the answer it expects instead of a numeric range.
     */
    public boolean hasExpectedResult() {
        return expectedResult != null && !expectedResult.isBlank();
    }

    /** QA D213 / D834: something a result can be graded against, a reference range or an expected answer. */
    public boolean hasGradingBasis() {
        return (referenceRanges != null && !referenceRanges.isEmpty()) || hasExpectedResult();
    }

    /**
     * D48: a test may be ordered only once the approval workflow has made it
     * ACTIVE (and nobody has switched it off). A DRAFT, a definition still in
     * review, a rejected or a retired one is not orderable, whatever the
     * {@code active} flag says.
     */
    public boolean isOrderable() {
        return active && approvalStatus == LabTestDefinitionApprovalStatus.ACTIVE;
    }

    @PrePersist
    @PreUpdate
    private void validate() {
        // Normalize key fields for consistency
        if (testCode != null) testCode = testCode.trim().toUpperCase();
        if (name != null) name = name.trim();
        // QA D836: a category and a sample type are labels, kept as typed
        // ("Hématologie", "Sang total (EDTA)"); only the code is normalised.
        if (category != null) category = category.trim();
        if (sampleType != null) sampleType = sampleType.trim();
        if (expectedResult != null) {
            String trimmed = expectedResult.trim();
            expectedResult = trimmed.isEmpty() ? null : trimmed;
        }
        if (loincCode != null) {
            String trimmed = loincCode.trim();
            loincCode = trimmed.isEmpty() ? null : trimmed;
        }
        if (loincDisplay != null) {
            String trimmed = loincDisplay.trim();
            loincDisplay = trimmed.isEmpty() ? null : trimmed;
        }

        // D73: hospital is NOT cleared here. This hook nulled hospital_id on
        // every insert and update, so the service's per-hospital scoping (D30)
        // never reached the database and every approval step re-globalised a
        // repaired row. A null hospital is the platform catalog; a set one is
        // that hospital's own definition.

        if (referenceRanges == null) {
            referenceRanges = new ArrayList<>();
        }
    }
}
