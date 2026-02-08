package com.example.hms.model.neonatal;

import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.enums.NewbornAlertType;
import com.example.hms.enums.NewbornEducationTopic;
import com.example.hms.enums.NewbornFollowUpAction;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "newborn_assessments",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_newborn_assessment_patient", columnList = "patient_id"),
        @Index(name = "idx_newborn_assessment_hospital", columnList = "hospital_id"),
        @Index(name = "idx_newborn_assessment_time", columnList = "assessment_time DESC")
    }
)
@EqualsAndHashCode(callSuper = true, exclude = {
    "patient", "hospital", "registration", "recordedByStaff", "documentedBy", "alerts", "followUpActions", "parentEducationTopics"
})
public class NewbornAssessment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_newborn_assessment_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_newborn_assessment_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registration_id",
        foreignKey = @ForeignKey(name = "fk_newborn_assessment_registration"))
    private PatientHospitalRegistration registration;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_newborn_assessment_staff"))
    private Staff recordedByStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "documented_by_user_id",
        foreignKey = @ForeignKey(name = "fk_newborn_assessment_user"))
    private User documentedBy;

    @Column(name = "assessment_time", nullable = false)
    private LocalDateTime assessmentTime;

    @Column(name = "documented_at", nullable = false)
    private LocalDateTime documentedAt;

    @Builder.Default
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry = false;

    @Column(name = "original_entry_time")
    private LocalDateTime originalEntryTime;

    @Column(name = "apgar_one_minute")
    private Integer apgarOneMinute;

    @Column(name = "apgar_five_minute")
    private Integer apgarFiveMinute;

    @Column(name = "apgar_ten_minute")
    private Integer apgarTenMinute;

    @Column(name = "apgar_notes", columnDefinition = "TEXT")
    private String apgarNotes;

    @Column(name = "temperature_celsius")
    private Double temperatureCelsius;

    @Column(name = "heart_rate_bpm")
    private Integer heartRateBpm;

    @Column(name = "respirations_per_min")
    private Integer respirationsPerMin;

    @Column(name = "systolic_bp_mm_hg")
    private Integer systolicBpMmHg;

    @Column(name = "diastolic_bp_mm_hg")
    private Integer diastolicBpMmHg;

    @Column(name = "oxygen_saturation_percent")
    private Integer oxygenSaturationPercent;

    @Column(name = "glucose_mg_dl")
    private Integer glucoseMgDl;

    @Column(name = "exam_general_appearance", columnDefinition = "TEXT")
    private String examGeneralAppearance;

    @Column(name = "exam_head_neck", columnDefinition = "TEXT")
    private String examHeadNeck;

    @Column(name = "exam_chest_lungs", columnDefinition = "TEXT")
    private String examChestLungs;

    @Column(name = "exam_cardiac", columnDefinition = "TEXT")
    private String examCardiac;

    @Column(name = "exam_abdomen", columnDefinition = "TEXT")
    private String examAbdomen;

    @Column(name = "exam_genitourinary", columnDefinition = "TEXT")
    private String examGenitourinary;

    @Column(name = "exam_skin", columnDefinition = "TEXT")
    private String examSkin;

    @Column(name = "exam_neurological", columnDefinition = "TEXT")
    private String examNeurological;

    @Column(name = "exam_musculoskeletal", columnDefinition = "TEXT")
    private String examMusculoskeletal;

    @Column(name = "exam_notes", columnDefinition = "TEXT")
    private String examNotes;

    @Builder.Default
    @Column(name = "escalation_recommended", nullable = false)
    private boolean escalationRecommended = false;

    @Builder.Default
    @Column(name = "respiratory_support_initiated", nullable = false)
    private boolean respiratorySupportInitiated = false;

    @Builder.Default
    @Column(name = "glucose_protocol_initiated", nullable = false)
    private boolean glucoseProtocolInitiated = false;

    @Builder.Default
    @Column(name = "thermoregulation_support_initiated", nullable = false)
    private boolean thermoregulationSupportInitiated = false;

    @Column(name = "follow_up_notes", columnDefinition = "TEXT")
    private String followUpNotes;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @Enumerated(EnumType.STRING)
    @CollectionTable(
        name = "newborn_assessment_follow_up_actions",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "assessment_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_newborn_followup_assessment"))
    )
    @Column(name = "action", nullable = false, length = 64)
    private Set<NewbornFollowUpAction> followUpActions = new HashSet<>();

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @Enumerated(EnumType.STRING)
    @CollectionTable(
        name = "newborn_assessment_education_topics",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "assessment_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_newborn_education_assessment"))
    )
    @Column(name = "topic", nullable = false, length = 80)
    private Set<NewbornEducationTopic> parentEducationTopics = new HashSet<>();

    @Column(name = "parent_education_notes", columnDefinition = "TEXT")
    private String parentEducationNotes;

    @Builder.Default
    @Column(name = "parent_education_completed", nullable = false)
    private boolean parentEducationCompleted = false;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "newborn_assessment_alerts",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "assessment_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_newborn_alert_assessment"))
    )
    @OrderColumn(name = "alert_order")
    private List<NewbornAssessmentAlert> alerts = new ArrayList<>();

    @PrePersist
    @PreUpdate
    void normalize() {
        if (documentedAt == null) {
            documentedAt = LocalDateTime.now();
        }
        if (assessmentTime == null) {
            assessmentTime = documentedAt;
        }
        if (lateEntry && originalEntryTime == null) {
            originalEntryTime = assessmentTime;
        }
        apgarNotes = trim(apgarNotes);
        examGeneralAppearance = trim(examGeneralAppearance);
        examHeadNeck = trim(examHeadNeck);
        examChestLungs = trim(examChestLungs);
        examCardiac = trim(examCardiac);
        examAbdomen = trim(examAbdomen);
        examGenitourinary = trim(examGenitourinary);
        examSkin = trim(examSkin);
        examNeurological = trim(examNeurological);
        examMusculoskeletal = trim(examMusculoskeletal);
        examNotes = trim(examNotes);
        followUpNotes = trim(followUpNotes);
        parentEducationNotes = trim(parentEducationNotes);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    public Set<NewbornFollowUpAction> getFollowUpActions() {
        return followUpActions == null ? Set.of() : Set.copyOf(followUpActions);
    }

    public void setFollowUpActions(Set<NewbornFollowUpAction> followUpActions) {
        this.followUpActions = followUpActions == null ? new HashSet<>() : new HashSet<>(followUpActions);
    }

    public void addFollowUpAction(NewbornFollowUpAction action) {
        if (action != null) {
            followUpActions.add(action);
        }
    }

    public void addFollowUpActions(Collection<NewbornFollowUpAction> actions) {
        if (actions != null && !actions.isEmpty()) {
            actions.stream().filter(Objects::nonNull).forEach(followUpActions::add);
        }
    }

    public Set<NewbornEducationTopic> getParentEducationTopics() {
        return parentEducationTopics == null ? Set.of() : Set.copyOf(parentEducationTopics);
    }

    public void setParentEducationTopics(Set<NewbornEducationTopic> parentEducationTopics) {
        this.parentEducationTopics = parentEducationTopics == null ? new HashSet<>() : new HashSet<>(parentEducationTopics);
    }

    public void addParentEducationTopic(NewbornEducationTopic topic) {
        if (topic != null) {
            parentEducationTopics.add(topic);
        }
    }

    public void addParentEducationTopics(Collection<NewbornEducationTopic> topics) {
        if (topics != null && !topics.isEmpty()) {
            topics.stream().filter(Objects::nonNull).forEach(parentEducationTopics::add);
        }
    }

    public List<NewbornAssessmentAlert> getAlerts() {
        return alerts == null ? List.of() : List.copyOf(alerts);
    }

    public void setAlerts(List<NewbornAssessmentAlert> alerts) {
        this.alerts = alerts == null ? new ArrayList<>() : new ArrayList<>(alerts);
    }

    public void addAlert(NewbornAssessmentAlert alert) {
        if (alert == null) {
            return;
        }
        NewbornAssessmentAlert sanitized = NewbornAssessmentAlert.builder()
            .type(alert.getType() != null ? alert.getType() : NewbornAlertType.GENERAL)
            .severity(alert.getSeverity() != null ? alert.getSeverity() : NewbornAlertSeverity.INFO)
            .code(alert.getCode())
            .message(alert.getMessage() != null ? alert.getMessage().trim() : null)
            .triggeredBy(alert.getTriggeredBy())
            .createdAt(alert.getCreatedAt())
            .build();
        alerts.add(sanitized);
    }

    public void markEscalationRecommended() {
        this.escalationRecommended = true;
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof NewbornAssessment;
    }
}
