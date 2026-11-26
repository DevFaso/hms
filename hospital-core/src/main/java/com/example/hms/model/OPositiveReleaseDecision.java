package com.example.hms.model;

import com.example.hms.enums.AntiDDecision;
import com.example.hms.enums.ONegativeStatus;
import com.example.hms.enums.OPositiveDecisionBasis;
import com.example.hms.enums.OPositiveReviewPriority;
import com.example.hms.enums.OPositiveRiskTier;
import com.example.hms.enums.OPositiveTierReason;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The decision behind one O RhD positive red-cell bag released in an emergency (V380), beside its
 * {@link EmergencyBloodRelease}: the tier applied and the settings in force, the sex and age of the patient
 * then, who decided, the verbal order and its countersignature, the review by the biologist and the follow-up
 * of the exposure. What was decided at release is never rewritten; the countersignature, the review and the
 * follow-up are each written once.
 *
 * <p>People are kept by id: the rows are read by a scheduler and after commits, where no session is open.
 */
@Entity
@Table(
    name = "o_positive_release_decisions",
    schema = "clinical",
    uniqueConstraints = @UniqueConstraint(name = "uq_o_positive_decision_release", columnNames = "release_id"),
    indexes = {
        @Index(name = "idx_o_positive_decision_hospital", columnList = "hospital_id, issued_at"),
        @Index(name = "idx_o_positive_decision_patient", columnList = "patient_id, rhd_exposure")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"release", "hospital", "reviewNote", "followUpNote"})
public class OPositiveReleaseDecision extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "release_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_o_positive_decision_release"))
    private EmergencyBloodRelease release;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "blood_unit_id", nullable = false)
    private UUID bloodUnitId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_o_positive_decision_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_tier", nullable = false, length = 20)
    private OPositiveRiskTier riskTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier_reason", nullable = false, length = 40)
    private OPositiveTierReason tierReason;

    @Column(name = "protocol_active", nullable = false)
    private boolean protocolActive;

    @Column(name = "female_age_cutoff", nullable = false)
    private int femaleAgeCutoff;

    @Column(name = "boys_low_risk", nullable = false)
    private boolean boysLowRisk;

    @Column(name = "review_deadline_hours", nullable = false)
    private int reviewDeadlineHours;

    /** MALE, FEMALE or UNKNOWN: what the record proved when the bag went out. */
    @Column(name = "patient_sex", nullable = false, length = 10)
    private String patientSex;

    @Column(name = "patient_age_years")
    private Integer patientAgeYears;

    /** The RhD the laboratory had on record then: POSITIVE, NEGATIVE, or null when untyped. */
    @Column(name = "patient_rh", length = 10)
    private String patientRh;

    @Enumerated(EnumType.STRING)
    @Column(name = "o_negative_status", nullable = false, length = 30)
    private ONegativeStatus negativeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_basis", nullable = false, length = 30)
    private OPositiveDecisionBasis decisionBasis;

    @Column(name = "issued_by_user_id")
    private UUID issuedByUserId;

    /** Who decided: the issuer under the protocol, or the named physician. Null while the authority is owed. */
    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "biologist_user_id")
    private UUID biologistUserId;

    @Column(name = "verbal_order", nullable = false)
    private boolean verbalOrder;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "countersign_due_at")
    private LocalDateTime countersignDueAt;

    @Column(name = "countersigned_by_user_id")
    private UUID countersignedByUserId;

    @Column(name = "countersigned_at")
    private LocalDateTime countersignedAt;

    /** When the overdue countersignature was escalated to the laboratory director. */
    @Column(name = "countersign_escalated_at")
    private LocalDateTime countersignEscalatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_priority", nullable = false, length = 12)
    private OPositiveReviewPriority reviewPriority;

    @Column(name = "review_due_at", nullable = false)
    private LocalDateTime reviewDueAt;

    @Column(name = "reviewed_by_user_id")
    private UUID reviewedByUserId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** The words of the biologist: a clinical narrative, so encrypted. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    /** The permanent flag: this release exposed a patient at risk to RhD positive red cells. Never cleared. */
    @Column(name = "rhd_exposure", nullable = false)
    private boolean rhdExposure;

    @Column(name = "follow_up_required", nullable = false)
    private boolean followUpRequired;

    @Column(name = "antibody_screen_due_from")
    private LocalDate antibodyScreenDueFrom;

    @Column(name = "antibody_screen_due_by")
    private LocalDate antibodyScreenDueBy;

    @Column(name = "antibody_screen_done_at")
    private LocalDateTime antibodyScreenDoneAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "anti_d_decision", length = 30)
    private AntiDDecision antiDDecision;

    @Column(name = "anti_d_decided_by_user_id")
    private UUID antiDDecidedByUserId;

    @Column(name = "anti_d_decided_at")
    private LocalDateTime antiDDecidedAt;

    @Column(name = "patient_informed_by_user_id")
    private UUID patientInformedByUserId;

    @Column(name = "patient_informed_at")
    private LocalDateTime patientInformedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "follow_up_note", columnDefinition = "TEXT")
    private String followUpNote;

    public boolean countersignOwed() {
        return countersignDueAt != null && countersignedAt == null;
    }

    public boolean reviewOwed() {
        return reviewedAt == null;
    }

    /** The clinician still owes the anti-D or exchange decision, or telling the patient. */
    public boolean followUpOwed() {
        return followUpRequired && (antiDDecision == null || patientInformedAt == null);
    }
}
