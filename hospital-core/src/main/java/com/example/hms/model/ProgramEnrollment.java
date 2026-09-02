package com.example.hms.model;

import com.example.hms.enums.CareProgram;
import com.example.hms.enums.ProgramEnrollmentStatus;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;

/**
 * One patient's enrolment in one care programme at one hospital (Tier 2
 * item 35).
 *
 * <p>The registry row. A patient can be in several programmes at once
 * (hypertension and diabetes commonly travel together) and can be re-enrolled
 * in a programme they previously left — TB especially — so the uniqueness
 * rule is one <b>ACTIVE</b> enrolment per (patient, hospital, programme),
 * enforced by a partial unique index in V146 rather than a plain constraint.
 * History rows in closed states stay behind as the record of prior episodes.
 *
 * <p>{@code nextExpectedVisit} is what item 36's care-gap sweep will read:
 * an ACTIVE enrolment whose next expected visit is in the past IS the care
 * gap. It is advanced by {@code recordVisit}, never by a scheduler — the
 * fact being recorded is "the patient was seen", and only a person knows
 * that.
 */
@Entity
@Table(
    name = "program_enrollments",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_prog_enroll_patient",  columnList = "patient_id"),
        @Index(name = "idx_prog_enroll_hospital", columnList = "hospital_id"),
        @Index(name = "idx_prog_enroll_program",  columnList = "hospital_id, program, status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "enrolledBy"})
public class ProgramEnrollment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_prog_enroll_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_prog_enroll_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "program", nullable = false, length = 20)
    private CareProgram program;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ProgramEnrollmentStatus status = ProgramEnrollmentStatus.ACTIVE;

    /** The clinical enrolment date — may predate the row for backfilled paper registers. */
    @Column(name = "enrolled_on", nullable = false)
    private LocalDate enrolledOn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrolled_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_prog_enroll_staff"))
    private Staff enrolledBy;

    /**
     * Days between programme visits, typed in by the enrolling clinician.
     * Deliberately not defaulted per programme — see {@link CareProgram}.
     */
    @Column(name = "visit_cadence_days", nullable = false)
    private Integer visitCadenceDays;

    /** The last programme visit that actually happened. Null until the first one. */
    @Column(name = "last_visit_on")
    private LocalDate lastVisitOn;

    /** What item 36's care-gap sweep reads. In the past on an ACTIVE row = defaulter. */
    @Column(name = "next_expected_visit", nullable = false)
    private LocalDate nextExpectedVisit;

    /** Clinical narrative, so encrypted at rest; TEXT because AES-GCM+Base64 outgrows the plaintext cap. */
    @Size(max = 500)
    @Column(name = "notes", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String notes;

    /** Set when the enrolment leaves ACTIVE, alongside the closed status. */
    @Column(name = "closed_on")
    private LocalDate closedOn;

    /** Also a clinical narrative ("traced twice by phone..."), encrypted like the notes. */
    @Size(max = 500)
    @Column(name = "closure_reason", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String closureReason;
}
