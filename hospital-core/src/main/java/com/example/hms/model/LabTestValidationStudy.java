package com.example.hms.model;

import com.example.hms.enums.ValidationStudyType;
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
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A CLIA/CLSI validation study record attached to a {@link LabTestDefinition}.
 *
 * <p>Represents one documented study (precision, accuracy, reference range, etc.)
 * performed to validate a test method as required by CAP/ISO 15189 accreditation.
 * Multiple studies may link to the same definition over its lifecycle.</p>
 */
@Entity
@Table(
    name = "lab_test_validation_studies",
    schema = "lab",
    indexes = {
        @Index(name = "idx_lab_val_study_def",  columnList = "lab_test_def_id"),
        @Index(name = "idx_lab_val_study_type", columnList = "study_type"),
        @Index(name = "idx_lab_val_study_date", columnList = "study_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = "labTestDefinition")
public class LabTestValidationStudy extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "lab_test_def_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_val_study_def")
    )
    private LabTestDefinition labTestDefinition;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "study_type", nullable = false, length = 50)
    private ValidationStudyType studyType;

    @NotNull
    @Column(name = "study_date", nullable = false)
    private LocalDate studyDate;

    /** UUID of the staff member who performed the study. */
    @Column(name = "performed_by_user_id")
    private UUID performedByUserId;

    /** Display name (firstName + lastName) captured at study time. */
    @Column(name = "performed_by_display", length = 255)
    private String performedByDisplay;

    /** High-level summary / conclusion of the study. */
    @Column(name = "summary", length = 2048)
    private String summary;

    /**
     * Flexible JSON blob containing CLSI protocol metrics
     * (e.g. SD, CV%, bias%, linearity points).
     */
    @Column(name = "result_data", columnDefinition = "TEXT")
    private String resultData;

    /** Whether the study passed acceptance criteria. */
    @Column(name = "passed", nullable = false)
    @Builder.Default
    private boolean passed = false;

    @Column(name = "notes", length = 2048)
    private String notes;
}
