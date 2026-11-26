package com.example.hms.model;

import com.example.hms.enums.IncompatibleAntibodyStatus;
import com.example.hms.enums.IncompatibleMonitoringOutcome;
import com.example.hms.enums.IncompatibleReleaseBasis;
import com.example.hms.enums.PatientInformedStatus;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The decision behind one red-cell bag released despite an incompatible crossmatch (V381), beside its
 * {@link EmergencyBloodRelease}: on whose authority (a named physician, or the fallback of the hospital with the
 * authority owed), what the record said about antibodies then, the bag chosen and why, whether the patient was
 * informed, the countersignature and the monitoring afterwards. What was decided at release is never rewritten;
 * the countersignature and the monitoring are each written once.
 *
 * <p>People are kept by id: the rows are read by a scheduler and after commits, where no session is open.
 */
@Entity
@Table(
    name = "incompatible_release_decisions",
    schema = "clinical",
    uniqueConstraints = @UniqueConstraint(name = "uq_incompatible_decision_release", columnNames = "release_id"),
    indexes = {
        @Index(name = "idx_incompatible_decision_hospital", columnList = "hospital_id, issued_at"),
        @Index(name = "idx_incompatible_decision_patient", columnList = "patient_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"release", "hospital", "unreachableReason", "antibodyDetail", "crossmatchFinding",
    "unitChoiceReason", "monitoringNote"})
public class IncompatibleReleaseDecision extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "release_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_incompatible_decision_release"))
    private EmergencyBloodRelease release;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "blood_unit_id", nullable = false)
    private UUID bloodUnitId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_incompatible_decision_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_basis", nullable = false, length = 30)
    private IncompatibleReleaseBasis decisionBasis;

    /** The setting of the hospital when the bag went out. */
    @Column(name = "fallback_enabled", nullable = false)
    private boolean fallbackEnabled;

    @Column(name = "countersign_deadline_hours", nullable = false)
    private int countersignDeadlineHours;

    @Column(name = "issued_by_user_id", nullable = false)
    private UUID issuedByUserId;

    /** The physician or laboratory director named before the issue; null on the fallback. */
    @Column(name = "authoriser_user_id")
    private UUID authoriserUserId;

    /** The fallback: the role under which the issuer declared that no physician could be reached. */
    @Column(name = "declarer_role", length = 40)
    private String declarerRole;

    /** The fallback: who was called and when, in the words of the issuer. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "unreachable_reason", columnDefinition = "TEXT")
    private String unreachableReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "antibody_status", nullable = false, length = 30)
    private IncompatibleAntibodyStatus antibodyStatus;

    /** What the laboratory wrote with the positive screen, when it wrote anything. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "antibody_detail", columnDefinition = "TEXT")
    private String antibodyDetail;

    /** The finding of the crossmatch of this bag, as the laboratory recorded it. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "crossmatch_finding", columnDefinition = "TEXT")
    private String crossmatchFinding;

    @Column(name = "recipient_abo", length = 5)
    private String recipientAbo;

    @Column(name = "recipient_rh", length = 10)
    private String recipientRh;

    @Column(name = "unit_abo", nullable = false, length = 5)
    private String unitAbo;

    @Column(name = "unit_rh", length = 10)
    private String unitRh;

    /** Always true: the database refuses a row for a bag the ABO group of the patient excludes. */
    @Column(name = "abo_compatible", nullable = false)
    private boolean aboCompatible;

    @Column(name = "d_matched", nullable = false)
    private boolean rhdMatched;

    @Column(name = "rh_kell_matched")
    private Boolean rhKellMatched;

    @Column(name = "antigen_negative")
    private Boolean antigenNegative;

    /** No bag with an incompatible result on the request ranked better when this one went out. */
    @Column(name = "least_incompatible", nullable = false)
    private boolean leastIncompatible;

    @Column(name = "candidates_considered", nullable = false)
    private int candidatesConsidered;

    /** Why this bag, in the words of the issuer. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "unit_choice_reason", nullable = false, columnDefinition = "TEXT")
    private String unitChoiceReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "patient_informed", nullable = false, length = 20)
    private PatientInformedStatus patientInformed;

    /** When the patient, not told at release, was told afterwards. */
    @Column(name = "patient_informed_at")
    private LocalDateTime patientInformedAt;

    @Column(name = "patient_informed_by_user_id")
    private UUID patientInformedByUserId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    /** The fallback only: when the countersignature of a physician is due. */
    @Column(name = "countersign_due_at")
    private LocalDateTime countersignDueAt;

    @Column(name = "countersigned_by_user_id")
    private UUID countersignedByUserId;

    @Column(name = "countersigned_at")
    private LocalDateTime countersignedAt;

    @Column(name = "countersign_escalated_at")
    private LocalDateTime countersignEscalatedAt;

    @Column(name = "notices_filed_at")
    private LocalDateTime noticesFiledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "monitoring_outcome", length = 20)
    private IncompatibleMonitoringOutcome monitoringOutcome;

    /** The checks of the rule's checklist recorded as done, by code, comma-separated. */
    @Column(name = "monitoring_checks", length = 300)
    private String monitoringChecks;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "monitoring_note", columnDefinition = "TEXT")
    private String monitoringNote;

    @Column(name = "monitoring_recorded_by_user_id")
    private UUID monitoringRecordedByUserId;

    @Column(name = "monitoring_recorded_at")
    private LocalDateTime monitoringRecordedAt;

    public boolean countersignOwed() {
        return countersignDueAt != null && countersignedAt == null;
    }

    public boolean monitoringOwed() {
        return monitoringOutcome == null;
    }
}
