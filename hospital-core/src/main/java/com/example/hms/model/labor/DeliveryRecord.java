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

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 30)
    private DeliveryMode deliveryMode;

    @Column(name = "live_birth", nullable = false)
    @Builder.Default
    private boolean liveBirth = true;

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
        if (alerts == null) {
            alerts = new ArrayList<>();
        }
        alerts.add(LaborAlert.builder()
            .type(type)
            .severity(severity)
            .code(code)
            .message(message)
            .triggeredBy(triggeredBy)
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
