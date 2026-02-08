package com.example.hms.model.postpartum;

import com.example.hms.enums.PostpartumSchedulePhase;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks active postpartum monitoring plans to drive automated vital sign and assessment reminders.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "postpartum_care_plans",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_postpartum_plan_patient", columnList = "patient_id"),
        @Index(name = "idx_postpartum_plan_hospital", columnList = "hospital_id"),
        @Index(name = "idx_postpartum_plan_active", columnList = "active")
    }
)
@EqualsAndHashCode(callSuper = true, exclude = {"patient", "hospital", "registration", "observations"})
public class PostpartumCarePlan extends BaseEntity {

    public static final int IMMEDIATE_INTERVAL_MINUTES = 15;
    public static final int IMMEDIATE_WINDOW_MINUTES = 120;
    public static final int DEFAULT_SHIFT_FREQUENCY_MINUTES = 480; // once per 8 hour shift
    public static final int MIN_SHIFT_FREQUENCY_MINUTES = 120;
    public static final int MAX_SHIFT_FREQUENCY_MINUTES = 720;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_postpartum_plan_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_postpartum_plan_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registration_id",
        foreignKey = @ForeignKey(name = "fk_postpartum_plan_registration"))
    private PatientHospitalRegistration registration;

    @Column(name = "delivery_occurred_at")
    private LocalDateTime deliveryOccurredAt;

    @Column(name = "stabilization_achieved_at")
    private LocalDateTime stabilizationAchievedAt;

    @Builder.Default
    @Column(name = "immediate_window_completed", nullable = false)
    private boolean immediateWindowCompleted = false;

    @Builder.Default
    @Column(name = "immediate_observations_completed", nullable = false)
    private int immediateObservationsCompleted = 0;

    @Builder.Default
    @Column(name = "immediate_observation_target", nullable = false)
    private int immediateObservationTarget = IMMEDIATE_WINDOW_MINUTES / IMMEDIATE_INTERVAL_MINUTES;

    @Builder.Default
    @Column(name = "shift_frequency_minutes", nullable = false)
    private int shiftFrequencyMinutes = DEFAULT_SHIFT_FREQUENCY_MINUTES;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "active_phase", nullable = false, length = 40)
    private PostpartumSchedulePhase activePhase = PostpartumSchedulePhase.IMMEDIATE_RECOVERY;

    @Column(name = "next_due_at")
    private LocalDateTime nextDueAt;

    @Column(name = "overdue_since")
    private LocalDateTime overdueSince;

    @Column(name = "postpartum_visit_date")
    private LocalDate postpartumVisitDate;

    @Builder.Default
    @Column(name = "discharge_checklist_complete", nullable = false)
    private boolean dischargeChecklistComplete = false;

    @Builder.Default
    @Column(name = "hemorrhage_protocol_confirmed", nullable = false)
    private boolean hemorrhageProtocolConfirmed = false;

    @Builder.Default
    @Column(name = "uterotonic_availability_confirmed", nullable = false)
    private boolean uterotonicAvailabilityConfirmed = false;

    @Builder.Default
    @Column(name = "rh_immunoglobulin_completed", nullable = false)
    private boolean rhImmunoglobulinCompleted = false;

    @Builder.Default
    @Column(name = "immunizations_updated", nullable = false)
    private boolean immunizationsUpdated = false;

    @Builder.Default
    @Column(name = "contact_info_verified", nullable = false)
    private boolean contactInfoVerified = false;

    @Column(name = "follow_up_contact_method", length = 120)
    private String followUpContactMethod;

    @Column(name = "discharge_safety_notes", columnDefinition = "TEXT")
    private String dischargeSafetyNotes;

    @Builder.Default
    @Column(name = "mental_health_referral_outstanding", nullable = false)
    private boolean mentalHealthReferralOutstanding = false;

    @Builder.Default
    @Column(name = "social_support_referral_outstanding", nullable = false)
    private boolean socialSupportReferralOutstanding = false;

    @Builder.Default
    @Column(name = "pain_followup_outstanding", nullable = false)
    private boolean painFollowupOutstanding = false;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "last_observation_at")
    private LocalDateTime lastObservationAt;

    @Column(name = "escalation_reason", length = 500)
    private String escalationReason;

    @Builder.Default
    @OneToMany(mappedBy = "carePlan", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<PostpartumObservation> observations = new ArrayList<>();

    @PrePersist
    @PreUpdate
    void normalize() {
        if (followUpContactMethod != null) {
            followUpContactMethod = followUpContactMethod.trim();
        }
        if (dischargeSafetyNotes != null) {
            dischargeSafetyNotes = dischargeSafetyNotes.trim();
        }
        if (escalationReason != null) {
            escalationReason = escalationReason.trim();
        }
        if (immediateObservationTarget <= 0) {
            immediateObservationTarget = IMMEDIATE_WINDOW_MINUTES / IMMEDIATE_INTERVAL_MINUTES;
        }
        if (shiftFrequencyMinutes < MIN_SHIFT_FREQUENCY_MINUTES) {
            shiftFrequencyMinutes = MIN_SHIFT_FREQUENCY_MINUTES;
        }
        if (shiftFrequencyMinutes > MAX_SHIFT_FREQUENCY_MINUTES) {
            shiftFrequencyMinutes = MAX_SHIFT_FREQUENCY_MINUTES;
        }
    }

    public void incrementImmediateObservationCount() {
        immediateObservationsCompleted = Math.min(immediateObservationsCompleted + 1, immediateObservationTarget);
    }

    public int remainingImmediateObservations() {
        return Math.max(0, immediateObservationTarget - immediateObservationsCompleted);
    }

    public void markActivePhase(PostpartumSchedulePhase phase) {
        if (phase != null) {
            this.activePhase = phase;
        }
    }

    public void markClosed(LocalDateTime timestamp) {
        this.active = false;
        this.closedAt = timestamp != null ? timestamp : LocalDateTime.now();
        this.nextDueAt = null;
        this.overdueSince = null;
    }

    public void markEscalation(String reason) {
        this.escalationReason = reason;
    }

    public boolean isImmediatePhase() {
        return activePhase == PostpartumSchedulePhase.IMMEDIATE_RECOVERY;
    }

    public boolean isEnhancedMonitoring() {
        return activePhase == PostpartumSchedulePhase.ENHANCED_MONITORING;
    }
}
