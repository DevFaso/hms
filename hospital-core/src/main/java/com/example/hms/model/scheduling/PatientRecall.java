package com.example.hms.model.scheduling;

import com.example.hms.enums.RecallSource;
import com.example.hms.enums.RecallStatus;
import com.example.hms.enums.RecallType;
import com.example.hms.model.Appointment;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.ProgramEnrollment;
import com.example.hms.model.Staff;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
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
 * One patient recall — a return visit the practice owes the patient
 * (P3 #22). The first recall concept in the system. Two feeds: the
 * checkout follow-up request (captured since MVP 6 and hardcoded to null
 * until V128) and manual desk entry. A sweep notifies the patient as the
 * due date approaches; booking a slot against the recall marks it
 * SCHEDULED and links the appointment.
 */
@Entity
@Table(
    name = "patient_recalls",
    schema = "scheduling",
    indexes = {
        @Index(name = "idx_recall_patient",  columnList = "patient_id"),
        @Index(name = "idx_recall_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "department", "preferredProvider", "encounter", "linkedAppointment", "programEnrollment"})
public class PatientRecall extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_recall_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_recall_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id",
        foreignKey = @ForeignKey(name = "fk_recall_department"))
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preferred_provider_id",
        foreignKey = @ForeignKey(name = "fk_recall_provider"))
    private Staff preferredProvider;

    /** The visit this recall grew out of, when it came from checkout. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id",
        foreignKey = @ForeignKey(name = "fk_recall_encounter"))
    private Encounter encounter;

    /**
     * The overdue programme enrolment this recall traces, when the care-gap
     * sweep created it (Tier 2 item 36); null for clinician-created recalls.
     * V147's partial unique index holds one recall per (enrolment, due date).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_enrollment_id",
        foreignKey = @ForeignKey(name = "fk_recall_program_enrollment"))
    private ProgramEnrollment programEnrollment;

    @Enumerated(EnumType.STRING)
    @Column(name = "recall_type", nullable = false, length = 30)
    @Builder.Default
    private RecallType recallType = RecallType.FOLLOW_UP;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RecallStatus status = RecallStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private RecallSource source = RecallSource.MANUAL;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Size(max = 500)
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Size(max = 500)
    @Column(name = "notes", length = 500)
    private String notes;

    /** Exactly-once stamp for the notification sweep (the V112 idiom). */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_appointment_id",
        foreignKey = @ForeignKey(name = "fk_recall_appointment"))
    private Appointment linkedAppointment;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by_user_id")
    private UUID closedByUserId;

    @Column(name = "created_by", length = 255)
    private String createdBy;
}
