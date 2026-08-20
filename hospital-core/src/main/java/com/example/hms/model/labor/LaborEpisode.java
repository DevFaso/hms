package com.example.hms.model.labor;

import com.example.hms.enums.LaborOutcome;
import com.example.hms.enums.LaborStatus;
import com.example.hms.enums.MembraneStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

/**
 * A labor episode — the intrapartum aggregate that partograph entries and
 * the delivery record hang off (P1 #6, roadmap row 41 "antepartum /
 * partogram" surface). Also serves as the pregnancy-episode aggregate the
 * OB scope audit flagged as missing: {@link #outcome} is the
 * "Pregnancy.outcome" the audit deferred for lack of a Pregnancy entity.
 * <p>
 * Modeled on {@link com.example.hms.model.postpartum.PostpartumCarePlan}.
 */
@Entity
@Table(
    name = "labor_episodes",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_labor_episode_patient",  columnList = "patient_id"),
        @Index(name = "idx_labor_episode_hospital", columnList = "hospital_id"),
        @Index(name = "idx_labor_episode_status",   columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "registration", "maternalHistory", "admittedByStaff", "documentedBy"})
public class LaborEpisode extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_labor_episode_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_labor_episode_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registration_id",
        foreignKey = @ForeignKey(name = "fk_labor_episode_registration"))
    private PatientHospitalRegistration registration;

    /**
     * Snapshot link to the maternal-history version current at admission.
     * Nullable — labor can be recorded for a walk-in with no history on file.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "maternal_history_id",
        foreignKey = @ForeignKey(name = "fk_labor_episode_maternal_history"))
    private MaternalHistory maternalHistory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admitted_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_labor_episode_staff"))
    private Staff admittedByStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "documented_by_user_id",
        foreignKey = @ForeignKey(name = "fk_labor_episode_user"))
    private User documentedBy;

    @Column(name = "labor_onset_at")
    private LocalDateTime laborOnsetAt;

    @Column(name = "admitted_at", nullable = false)
    private LocalDateTime admittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "membrane_status", length = 30)
    private MembraneStatus membraneStatus;

    @Column(name = "membrane_rupture_at")
    private LocalDateTime membraneRuptureAt;

    /**
     * Point-in-time clinical facts snapshotted from the current
     * MaternalHistory version at episode start (not re-derived later).
     */
    @Column(name = "gestational_age_weeks")
    private Integer gestationalAgeWeeks;

    @Column(name = "gravida")
    private Integer gravida;

    @Column(name = "para")
    private Integer para;

    /**
     * When the active phase began — set by the first partograph entry
     * recording cervical dilation ≥ 4 cm. Anchors the WHO alert line
     * (1 cm/h from 4 cm) and the action line (4 h to its right).
     */
    @Column(name = "active_phase_start_at")
    private LocalDateTime activePhaseStartAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private LaborStatus status = LaborStatus.ACTIVE;

    /** Pregnancy outcome — populated when the delivery record is filed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 30)
    private LaborOutcome outcome;

    @Column(name = "risk_notes", columnDefinition = "TEXT")
    private String riskNotes;

    @PrePersist
    @PreUpdate
    void normalize() {
        if (admittedAt == null) {
            admittedAt = LocalDateTime.now();
        }
        if (riskNotes != null) {
            riskNotes = riskNotes.trim();
        }
    }
}
