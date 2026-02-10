package com.example.hms.model.postpartum;

import com.example.hms.enums.PostpartumAlertSeverity;
import com.example.hms.enums.PostpartumAlertType;
import com.example.hms.enums.PostpartumBladderStatus;
import com.example.hms.enums.PostpartumEducationTopic;
import com.example.hms.enums.PostpartumFundusTone;
import com.example.hms.enums.PostpartumLochiaAmount;
import com.example.hms.enums.PostpartumLochiaCharacter;
import com.example.hms.enums.PostpartumMoodStatus;
import com.example.hms.enums.PostpartumSchedulePhase;
import com.example.hms.enums.PostpartumSleepQuality;
import com.example.hms.enums.PostpartumSupportStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Captures a bundled postpartum observation event including vitals, uterine assessment,
 * bleeding evaluation, psychosocial screening, and patient education log entries.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "postpartum_observations",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_postpartum_obs_patient", columnList = "patient_id"),
        @Index(name = "idx_postpartum_obs_hospital", columnList = "hospital_id"),
        @Index(name = "idx_postpartum_obs_plan", columnList = "care_plan_id"),
        @Index(name = "idx_postpartum_obs_observation_time", columnList = "observation_time")
    }
)
@EqualsAndHashCode(callSuper = true, exclude = {
    "patient", "hospital", "registration", "carePlan", "recordedByStaff", "documentedBy",
    "supersedesObservation", "alerts", "educationTopics"
})
public class PostpartumObservation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_postpartum_obs_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_postpartum_obs_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registration_id",
        foreignKey = @ForeignKey(name = "fk_postpartum_obs_registration"))
    private PatientHospitalRegistration registration;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "care_plan_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_postpartum_obs_plan"))
    private PostpartumCarePlan carePlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_postpartum_obs_staff"))
    private Staff recordedByStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "documented_by_user_id",
        foreignKey = @ForeignKey(name = "fk_postpartum_obs_user"))
    private User documentedBy;

    @Column(name = "observation_time", nullable = false)
    private LocalDateTime observationTime;

    @Column(name = "documented_at", nullable = false)
    private LocalDateTime documentedAt;

    @Builder.Default
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry = false;

    @Column(name = "original_entry_time")
    private LocalDateTime originalEntryTime;

    // ----- Vital signs -----
    @Column(name = "temperature_celsius")
    private Double temperatureCelsius;

    @Column(name = "systolic_bp_mm_hg")
    private Integer systolicBpMmHg;

    @Column(name = "diastolic_bp_mm_hg")
    private Integer diastolicBpMmHg;

    @Column(name = "pulse_bpm")
    private Integer pulseBpm;

    @Column(name = "respirations_per_min")
    private Integer respirationsPerMin;

    @Min(0)
    @Max(10)
    @Column(name = "pain_score")
    private Integer painScore;

    // ----- Uterine assessment -----
    @Column(name = "fundus_height_cm")
    private Integer fundusHeightCm;

    @Enumerated(EnumType.STRING)
    @Column(name = "fundus_tone", length = 32)
    private PostpartumFundusTone fundusTone;

    @Enumerated(EnumType.STRING)
    @Column(name = "bladder_status", length = 40)
    private PostpartumBladderStatus bladderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "lochia_amount", length = 24)
    private PostpartumLochiaAmount lochiaAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "lochia_character", length = 32)
    private PostpartumLochiaCharacter lochiaCharacter;

    @Size(max = 250)
    @Column(name = "lochia_notes", length = 250)
    private String lochiaNotes;

    @Size(max = 1000)
    @Column(name = "perineum_findings", length = 1000)
    private String perineumFindings;

    @Builder.Default
    @Column(name = "uterine_atony_suspected", nullable = false)
    private boolean uterineAtonySuspected = false;

    @Builder.Default
    @Column(name = "excessive_bleeding", nullable = false)
    private boolean excessiveBleeding = false;

    @Column(name = "estimated_blood_loss_ml")
    private Integer estimatedBloodLossMl;

    @Builder.Default
    @Column(name = "uterotonic_given", nullable = false)
    private boolean uterotonicGiven = false;

    @Builder.Default
    @Column(name = "hemorrhage_protocol_activated", nullable = false)
    private boolean hemorrhageProtocolActivated = false;

    // ----- Infection checks -----
    @Builder.Default
    @Column(name = "foul_lochia_odor", nullable = false)
    private boolean foulLochiaOdor = false;

    @Builder.Default
    @Column(name = "uterine_tenderness", nullable = false)
    private boolean uterineTenderness = false;

    @Builder.Default
    @Column(name = "chills_or_rigors", nullable = false)
    private boolean chillsOrRigors = false;

    // ----- Psychosocial & pain -----
    @Enumerated(EnumType.STRING)
    @Column(name = "mood_status", length = 24)
    private PostpartumMoodStatus moodStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "support_status", length = 24)
    private PostpartumSupportStatus supportStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "sleep_status", length = 24)
    private PostpartumSleepQuality sleepStatus;

    @Column(name = "psychosocial_notes", columnDefinition = "TEXT")
    private String psychosocialNotes;

    @Builder.Default
    @Column(name = "mental_health_referral_suggested", nullable = false)
    private boolean mentalHealthReferralSuggested = false;

    @Builder.Default
    @Column(name = "social_support_referral_suggested", nullable = false)
    private boolean socialSupportReferralSuggested = false;

    @Builder.Default
    @Column(name = "pain_management_referral_suggested", nullable = false)
    private boolean painManagementReferralSuggested = false;

    // ----- Education -----
    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @Enumerated(EnumType.STRING)
    @CollectionTable(
        name = "postpartum_observation_education_topics",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "observation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_postpartum_obs_education"))
    )
    @Column(name = "topic", nullable = false, length = 80)
    private Set<PostpartumEducationTopic> educationTopics = new HashSet<>();

    @Column(name = "education_notes", columnDefinition = "TEXT")
    private String educationNotes;

    @Builder.Default
    @Column(name = "education_completed", nullable = false)
    private boolean educationCompleted = false;

    // ----- Follow-up and discharge checks -----
    @Column(name = "postpartum_visit_date")
    private LocalDate postpartumVisitDate;

    @Builder.Default
    @Column(name = "discharge_checklist_complete", nullable = false)
    private boolean dischargeChecklistComplete = false;

    @Builder.Default
    @Column(name = "rh_immunoglobulin_completed", nullable = false)
    private boolean rhImmunoglobulinCompleted = false;

    @Builder.Default
    @Column(name = "immunizations_updated", nullable = false)
    private boolean immunizationsUpdated = false;

    @Builder.Default
    @Column(name = "hemorrhage_protocol_confirmed", nullable = false)
    private boolean hemorrhageProtocolConfirmed = false;

    @Builder.Default
    @Column(name = "uterotonic_availability_confirmed", nullable = false)
    private boolean uterotonicAvailabilityConfirmed = false;

    @Builder.Default
    @Column(name = "contact_info_verified", nullable = false)
    private boolean contactInfoVerified = false;

    @Column(name = "follow_up_contact_method", length = 120)
    private String followUpContactMethod;

    @Column(name = "discharge_safety_notes", columnDefinition = "TEXT")
    private String dischargeSafetyNotes;

    // ----- Scheduling snapshot -----
    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_phase_at_entry", length = 32)
    private PostpartumSchedulePhase schedulePhaseAtEntry;

    @Column(name = "next_due_at_snapshot")
    private LocalDateTime nextDueAtSnapshot;

    @Column(name = "overdue_since_snapshot")
    private LocalDateTime overdueSinceSnapshot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_observation_id",
        foreignKey = @ForeignKey(name = "fk_postpartum_obs_supersedes"))
    private PostpartumObservation supersedesObservation;

    @Column(name = "signoff_name", length = 200)
    private String signoffName;

    @Column(name = "signoff_credentials", length = 200)
    private String signoffCredentials;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "postpartum_observation_alerts",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "observation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_postpartum_obs_alert"))
    )
    @OrderColumn(name = "alert_order")
    private List<PostpartumObservationAlert> alerts = new ArrayList<>();

    @PrePersist
    @PreUpdate
    void normalize() {
        if (documentedAt == null) {
            documentedAt = LocalDateTime.now();
        }
        if (observationTime == null) {
            observationTime = documentedAt;
        }
        if (lateEntry && originalEntryTime == null) {
            originalEntryTime = observationTime;
        }
        educationNotes = trim(educationNotes);
        dischargeSafetyNotes = trim(dischargeSafetyNotes);
        psychosocialNotes = trim(psychosocialNotes);
        followUpContactMethod = trim(followUpContactMethod);
        signoffName = trim(signoffName);
        signoffCredentials = trim(signoffCredentials);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    public void addAlert(PostpartumObservationAlert alert) {
        if (alert == null) {
            return;
        }
        PostpartumObservationAlert sanitized = PostpartumObservationAlert.builder
                ()
            .type(alert.getType())
            .severity(alert.getSeverity() != null ? alert.getSeverity() : PostpartumAlertSeverity.INFO)
            .code(alert.getCode())
            .message(alert.getMessage() != null ? alert.getMessage().trim() : null)
            .triggeredBy(alert.getTriggeredBy())
            .createdAt(alert.getCreatedAt())
            .build();
        alerts.add(sanitized);
    }

    public void flagHemorrhageAlert(String message, String trigger) {
        addAlert(PostpartumObservationAlert.builder()
            .type(PostpartumAlertType.HEMORRHAGE)
            .severity(PostpartumAlertSeverity.URGENT)
            .message(message)
            .triggeredBy(trigger)
            .build());
    }

    public void flagInfectionAlert(String message, String trigger) {
        addAlert(PostpartumObservationAlert.builder()
            .type(PostpartumAlertType.INFECTION)
            .severity(PostpartumAlertSeverity.URGENT)
            .message(message)
            .triggeredBy(trigger)
            .build());
    }
}
