package com.example.hms.model.pro;

import com.example.hms.enums.pro.ProResponseSource;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.postpartum.PostpartumCarePlan;
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
import jakarta.persistence.Version;
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
 * One administration of a {@link ProInstrument} to one patient (Tier 2
 * item 47).
 *
 * <p>Stored, unlike NEWS2: an administered instrument's answers ARE the
 * record, and the trend across the postpartum cadence is the clinical
 * point. {@code answers} (item → chosen option, as JSON) and {@code notes}
 * are encrypted at rest — a self-harm item response is PHI of the most
 * sensitive kind; the score columns stay plain so the trend reads without
 * decrypting anything. Both are excluded from {@code toString()}.
 *
 * <p>A {@code criticalItemPositive} response follows the critical-lab
 * chain: notified on write, re-escalated by a sweep, until acknowledged.
 */
@Entity
@Table(
    name = "pro_responses",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_pro_responses_patient", columnList = "patient_id, administered_at"),
        @Index(name = "idx_pro_responses_care_plan", columnList = "postpartum_care_plan_id, administered_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"instrument", "patient", "hospital", "carePlan", "answers", "notes", "acknowledgementNote"})
public class ProResponse extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pro_response_instrument"))
    private ProInstrument instrument;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pro_response_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pro_response_hospital"))
    private Hospital hospital;

    /** The postpartum plan this screen belongs to, when the patient has one open. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "postpartum_care_plan_id",
        foreignKey = @ForeignKey(name = "fk_pro_response_care_plan"))
    private PostpartumCarePlan carePlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private ProResponseSource source;

    /** Language the items were presented in. */
    @Column(name = "language", length = 8)
    private String language;

    @Column(name = "administered_at", nullable = false)
    private LocalDateTime administeredAt;

    /** Null for a patient self-report. */
    @Column(name = "recorded_by_user_id")
    private UUID recordedByUserId;

    /** JSON object {itemNo: optionNo}. Encrypted at rest. */
    @Column(name = "answers", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String answers;

    @Column(name = "notes", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String notes;

    @Column(name = "total_score", nullable = false)
    private int totalScore;

    @Column(name = "answered_items", nullable = false)
    private int answeredItems;

    @Column(name = "total_items", nullable = false)
    private int totalItems;

    /** False when an item was left unanswered — the total is then a lower bound. */
    @Column(name = "complete", nullable = false)
    private boolean complete;

    @Column(name = "screen_positive", nullable = false)
    private boolean screenPositive;

    @Column(name = "critical_item_score")
    private Integer criticalItemScore;

    @Column(name = "critical_item_positive", nullable = false)
    @Builder.Default
    private boolean criticalItemPositive = false;

    /** When the write-time notification went out (or was attempted). */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "escalation_level", nullable = false)
    @Builder.Default
    private short escalationLevel = 0;

    /** Stamped on every round so the sweep's interval advances even with no resolvable recipient. */
    @Column(name = "last_escalation_at")
    private LocalDateTime lastEscalationAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by_user_id")
    private UUID acknowledgedByUserId;

    @Column(name = "acknowledged_by_display", length = 200)
    private String acknowledgedByDisplay;

    /** What was done about the disclosure. Encrypted at rest. */
    @Column(name = "acknowledgement_note", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String acknowledgementNote;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public boolean isAcknowledged() {
        return acknowledgedAt != null;
    }

    /** Escalation still owed: self-harm-positive and nobody has acknowledged. */
    public boolean isEscalationOpen() {
        return criticalItemPositive && acknowledgedAt == null;
    }
}
