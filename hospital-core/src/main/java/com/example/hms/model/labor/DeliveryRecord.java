package com.example.hms.model.labor;

import com.example.hms.enums.DeliveryMode;
import com.example.hms.enums.InfantSex;
import com.example.hms.enums.LaborAlertSeverity;
import com.example.hms.enums.LaborAlertType;
import com.example.hms.enums.PerinealTear;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The delivery record — birth-event facts owned by the accoucheur (P1 #6;
 * the feature behind the previously-dangling DOCUMENT_DELIVERY_NOTES
 * permission). One record per labor episode. APGAR here is the at-birth
 * scoring; the paediatric re-scorings live on NewbornAssessment, which
 * back-links via its new delivery_record_id FK.
 * <p>
 * Multiple births: v1 captures the first/primary infant's anthropometrics
 * here plus {@link #numberOfInfants}; per-infant detail belongs to the
 * linked NewbornAssessments.
 */
@Entity
@Table(
    name = "delivery_records",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_delivery_record_patient",  columnList = "patient_id"),
        @Index(name = "idx_delivery_record_hospital", columnList = "hospital_id"),
        @Index(name = "idx_delivery_record_birth",    columnList = "birth_date_time")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"episode", "patient", "hospital", "deliveredByStaff", "documentedBy", "alerts"})
public class DeliveryRecord extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "episode_id", nullable = false, unique = true,
        foreignKey = @ForeignKey(name = "fk_delivery_record_episode"))
    private LaborEpisode episode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_delivery_record_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_delivery_record_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivered_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_delivery_record_staff"))
    private Staff deliveredByStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "documented_by_user_id",
        foreignKey = @ForeignKey(name = "fk_delivery_record_user"))
    private User documentedBy;

    /* ── Birth event ───────────────────────────────────────────────────── */

    @Column(name = "birth_date_time", nullable = false)
    private LocalDateTime birthDateTime;

    /** False when the time was left blank and {@link #birthDateTime} holds the moment of documenting (V280). */
    @Column(name = "birth_time_stated", nullable = false)
    @Builder.Default
    private boolean birthTimeStated = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 30)
    private DeliveryMode deliveryMode;

    @Column(name = "live_birth", nullable = false)
    @Builder.Default
    private boolean liveBirth = true;

    /**
     * QA D1592: true while the live-born babies of this delivery are not
     * registered as patients (the registration runs after the delivery
     * commits; a failure leaves it set for reception). V289.
     */
    @Column(name = "newborn_registration_pending", nullable = false)
    @Builder.Default
    private boolean newbornRegistrationPending = false;

    @Column(name = "number_of_infants", nullable = false)
    @Builder.Default
    private int numberOfInfants = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "infant_sex", length = 20)
    private InfantSex infantSex;

    @Column(name = "birth_weight_grams")
    private Integer birthWeightGrams;

    @Column(name = "gestational_age_weeks_at_birth")
    private Integer gestationalAgeWeeksAtBirth;

    @Column(name = "apgar_one_minute")
    private Integer apgarOneMinute;

    @Column(name = "apgar_five_minute")
    private Integer apgarFiveMinute;

    /* ── Third stage + maternal condition ──────────────────────────────── */

    @Column(name = "placenta_delivered_at")
    private LocalDateTime placentaDeliveredAt;

    @Column(name = "placenta_complete")
    private Boolean placentaComplete;

    /** Active management of the third stage of labor (uterotonic given). */
    @Column(name = "uterotonic_given")
    private Boolean uterotonicGiven;

    @Column(name = "estimated_blood_loss_ml")
    private Integer estimatedBloodLossMl;

    @Enumerated(EnumType.STRING)
    @Column(name = "perineal_tear", length = 20)
    private PerinealTear perinealTear;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * QA r46 (D1712 follow-up, provisional): born before her arrival at the hospital of this episode, which
     * continues a labour from another hospital; the birth time may then precede the episode's start. V291.
     */
    @Column(name = "born_before_arrival", nullable = false)
    @Builder.Default
    private boolean bornBeforeArrival = false;

    @Column(name = "born_before_arrival_reason", length = 500)
    private String bornBeforeArrivalReason;

    /* ── Per-baby outcome (QA D1714, V293) ───────────────────────────────── */

    /**
     * QA D1714 (user decision 2026-11-13): the outcome of each baby, by birth order (1..{@link #numberOfInfants}).
     * Empty on a delivery recorded before V293: its single {@link #liveBirth} flag then answers, see
     * {@link #outcomeOf}. {@link #liveBirth} is kept true while at least one baby is live.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "delivery_record_baby_outcomes",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "delivery_record_id",
            foreignKey = @ForeignKey(name = "fk_delivery_baby_outcome_record")))
    @jakarta.persistence.MapKeyColumn(name = "birth_order")
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    @Builder.Default
    private java.util.Map<Integer, com.example.hms.enums.BabyOutcome> babyOutcomes = new java.util.HashMap<>();

    public java.util.Map<Integer, com.example.hms.enums.BabyOutcome> getBabyOutcomes() {
        return babyOutcomes == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(babyOutcomes);
    }

    /** Replace one baby's outcome (recording or a correction); {@link #liveBirth} follows. */
    public void putBabyOutcome(int birthOrder, com.example.hms.enums.BabyOutcome outcome) {
        if (babyOutcomes == null) {
            babyOutcomes = new java.util.HashMap<>();
        }
        babyOutcomes.put(birthOrder, outcome);
        liveBirth = liveOrders().length > 0;
    }

    /**
     * The outcome of the baby of this birth order. A delivery recorded per baby answers from its rows. One recorded
     * before (no rows) answers from its single flag: live, every baby is live; not live, the first baby is stillborn
     * and any later one is unknown (null): the flag cannot say, and nothing is guessed.
     */
    public com.example.hms.enums.BabyOutcome outcomeOf(int birthOrder) {
        if (birthOrder < 1 || birthOrder > Math.max(numberOfInfants, 1)) {
            return null;
        }
        if (babyOutcomes != null && !babyOutcomes.isEmpty()) {
            return babyOutcomes.get(birthOrder);
        }
        if (liveBirth) {
            return com.example.hms.enums.BabyOutcome.LIVE;
        }
        return birthOrder == 1 ? com.example.hms.enums.BabyOutcome.STILLBORN : null;
    }

    /** The birth orders of the live-born babies, ascending. */
    public int[] liveOrders() {
        return ordersWith(com.example.hms.enums.BabyOutcome.LIVE);
    }

    /** The birth orders of the stillborn babies, ascending. */
    public int[] stillbornOrders() {
        return ordersWith(com.example.hms.enums.BabyOutcome.STILLBORN);
    }

    private int[] ordersWith(com.example.hms.enums.BabyOutcome outcome) {
        if (babyOutcomes != null && !babyOutcomes.isEmpty()) {
            return babyOutcomes.entrySet().stream()
                .filter(e -> e.getValue() == outcome && e.getKey() != null && e.getKey() >= 1
                    && e.getKey() <= Math.max(numberOfInfants, 1))
                .mapToInt(java.util.Map.Entry::getKey).sorted().toArray();
        }
        return java.util.stream.IntStream.rangeClosed(1, Math.max(numberOfInfants, 1))
            .filter(order -> outcomeOf(order) == outcome).toArray();
    }

    /* ── Alerts ────────────────────────────────────────────────────────── */

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "delivery_record_alerts",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "delivery_record_id",
            foreignKey = @ForeignKey(name = "fk_delivery_alert_record")))
    @OrderColumn(name = "alert_order")
    @Builder.Default
    private List<LaborAlert> alerts = new ArrayList<>();

    public List<LaborAlert> getAlerts() {
        return alerts == null ? List.of() : List.copyOf(alerts);
    }

    public void setAlerts(List<LaborAlert> alerts) {
        this.alerts = alerts == null ? new ArrayList<>() : new ArrayList<>(alerts);
    }

    public void addAlert(LaborAlertType type, LaborAlertSeverity severity,
                         String code, String message, String triggeredBy) {
        addAlert(type, severity, code, message, triggeredBy, null);
    }

    /** QA D665: {@code messageArgs} are the alert text's own arguments, so a notice can re-render it per reader. */
    public void addAlert(LaborAlertType type, LaborAlertSeverity severity,
                         String code, String message, String triggeredBy, Object[] messageArgs) {
        if (alerts == null) {
            alerts = new ArrayList<>();
        }
        alerts.add(LaborAlert.builder()
            .type(type)
            .severity(severity)
            .code(code)
            .message(message)
            .triggeredBy(triggeredBy)
            .messageArgs(messageArgs)
            .createdAt(LocalDateTime.now())
            .build());
    }

    @PrePersist
    @PreUpdate
    void normalize() {
        if (birthDateTime == null) {
            birthDateTime = LocalDateTime.now();
        }
        if (notes != null) {
            notes = notes.trim();
        }
    }
}
