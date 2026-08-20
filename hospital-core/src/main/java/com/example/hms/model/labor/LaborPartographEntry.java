package com.example.hms.model.labor;

import com.example.hms.enums.LaborAlertSeverity;
import com.example.hms.enums.LaborAlertType;
import com.example.hms.enums.LiquorColour;
import com.example.hms.enums.MouldingDegree;
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
 * One timepoint on the WHO partograph: fetal condition, labor progress,
 * contractions, therapies, and maternal condition (P1 #6).
 * <p>
 * Modeled on {@link com.example.hms.model.postpartum.PostpartumObservation}:
 * same observation-time/late-entry quad and the same embedded alert list.
 */
@Entity
@Table(
    name = "labor_partograph_entries",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_partograph_entry_episode",  columnList = "episode_id"),
        @Index(name = "idx_partograph_entry_patient",  columnList = "patient_id"),
        @Index(name = "idx_partograph_entry_hospital", columnList = "hospital_id"),
        @Index(name = "idx_partograph_entry_time",     columnList = "observation_time")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"episode", "patient", "hospital", "recordedByStaff", "documentedBy", "alerts"})
public class LaborPartographEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "episode_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_partograph_entry_episode"))
    private LaborEpisode episode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_partograph_entry_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_partograph_entry_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_partograph_entry_staff"))
    private Staff recordedByStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "documented_by_user_id",
        foreignKey = @ForeignKey(name = "fk_partograph_entry_user"))
    private User documentedBy;

    /* ── Timepoint (house late-entry quad) ─────────────────────────────── */

    @Column(name = "observation_time", nullable = false)
    private LocalDateTime observationTime;

    @Column(name = "documented_at", nullable = false)
    private LocalDateTime documentedAt;

    @Column(name = "late_entry", nullable = false)
    @Builder.Default
    private boolean lateEntry = false;

    @Column(name = "original_entry_time")
    private LocalDateTime originalEntryTime;

    /* ── Fetal condition ───────────────────────────────────────────────── */

    @Column(name = "fetal_heart_rate_bpm")
    private Integer fetalHeartRateBpm;

    @Enumerated(EnumType.STRING)
    @Column(name = "liquor_colour", length = 30)
    private LiquorColour liquorColour;

    @Enumerated(EnumType.STRING)
    @Column(name = "moulding_degree", length = 20)
    private MouldingDegree mouldingDegree;

    /* ── Labor progress ────────────────────────────────────────────────── */

    /** Cervical dilation in cm, 0–10. */
    @Column(name = "cervical_dilation_cm")
    private Integer cervicalDilationCm;

    /** Descent of the head in fifths palpable above the brim, 0–5. */
    @Column(name = "descent_fifths")
    private Integer descentFifths;

    @Column(name = "contractions_per_ten_minutes")
    private Integer contractionsPerTenMinutes;

    @Column(name = "contraction_duration_seconds")
    private Integer contractionDurationSeconds;

    /* ── Therapies ─────────────────────────────────────────────────────── */

    @Column(name = "oxytocin_drops_per_minute")
    private Integer oxytocinDropsPerMinute;

    @Column(name = "drugs_given", columnDefinition = "TEXT")
    private String drugsGiven;

    @Column(name = "iv_fluids", columnDefinition = "TEXT")
    private String ivFluids;

    /* ── Maternal condition ────────────────────────────────────────────── */

    @Column(name = "pulse_bpm")
    private Integer pulseBpm;

    @Column(name = "systolic_bp_mm_hg")
    private Integer systolicBpMmHg;

    @Column(name = "diastolic_bp_mm_hg")
    private Integer diastolicBpMmHg;

    @Column(name = "temperature_celsius")
    private Double temperatureCelsius;

    @Column(name = "urine_output_ml")
    private Integer urineOutputMl;

    @Column(name = "urine_protein", length = 10)
    private String urineProtein;

    @Column(name = "urine_acetone", length = 10)
    private String urineAcetone;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /* ── Alerts ────────────────────────────────────────────────────────── */

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "labor_partograph_entry_alerts",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "entry_id",
            foreignKey = @ForeignKey(name = "fk_partograph_alert_entry")))
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
        LocalDateTime now = LocalDateTime.now();
        if (documentedAt == null) {
            documentedAt = now;
        }
        if (observationTime == null) {
            observationTime = documentedAt;
        }
        if (lateEntry && originalEntryTime == null) {
            originalEntryTime = observationTime;
        }
        if (drugsGiven != null) drugsGiven = drugsGiven.trim();
        if (ivFluids != null) ivFluids = ivFluids.trim();
        if (notes != null) notes = notes.trim();
        if (urineProtein != null) urineProtein = urineProtein.trim();
        if (urineAcetone != null) urineAcetone = urineAcetone.trim();
    }
}
