package com.example.hms.model;

import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * General Referral Entity - Multi-specialty referral system
 * Extends OB-GYN referral pattern to work across all clinical specialties
 */
@Entity
@Table(name = "general_referrals", indexes = {
    @Index(name = "idx_referral_patient", columnList = "patient_id"),
    @Index(name = "idx_referral_hospital", columnList = "hospital_id"),
    @Index(name = "idx_referral_receiving_hospital", columnList = "receiving_hospital_id"),
    @Index(name = "idx_referral_specialty", columnList = "target_specialty"),
    @Index(name = "idx_referral_status", columnList = "status"),
    @Index(name = "idx_referral_urgency", columnList = "urgency"),
    @Index(name = "idx_referral_referring_provider", columnList = "referring_provider_id"),
    @Index(name = "idx_referral_receiving_provider", columnList = "receiving_provider_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeneralReferral {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Patient being referred
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    /**
     * Hospital/organization initiating referral
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    /**
     * Hospital/organization receiving the referral (destination)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiving_hospital_id")
    private Hospital receivingHospital;

    /**
     * Department the referral originates from (source)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_department_id")
    private Department sourceDepartment;

    /**
     * Provider making the referral
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referring_provider_id", nullable = false)
    private Staff referringProvider;

    /**
     * Provider/specialist receiving referral (if known)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiving_provider_id")
    private Staff receivingProvider;

    /**
     * Target specialty for referral
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReferralSpecialty targetSpecialty;

    /**
     * Target department (optional, if within same hospital)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_department_id")
    private Department targetDepartment;

    /**
     * Target facility (if external referral)
     */
    @Column(length = 300)
    private String targetFacilityName;

    /**
     * Type of referral
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReferralType referralType;

    /**
     * Status of referral
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReferralStatus status = ReferralStatus.DRAFT;

    /**
     * Urgency level
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReferralUrgency urgency;

    /**
     * Reason for referral
     */
    @Column(nullable = false, length = 500)
    private String referralReason;

    /**
     * Clinical indication/question
     */
    @Column(length = 1000)
    private String clinicalIndication;

    /**
     * Relevant history and findings
     */
    @Column(length = 2000)
    private String clinicalSummary;

    /**
     * Current medications (as JSON array)
     */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, String>> currentMedications = new ArrayList<>();

    /**
     * Relevant diagnoses (ICD codes)
     */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, String>> diagnoses = new ArrayList<>();

    /**
     * Specific clinical question or request
     */
    @Column(length = 500)
    private String clinicalQuestion;

    /**
     * Anticipated treatment/procedure
     */
    @Column(length = 300)
    private String anticipatedTreatment;

    /**
     * When referral was submitted
     */
    private LocalDateTime submittedAt;

    /**
     * SLA due date based on urgency
     */
    private LocalDateTime slaDueAt;

    /**
     * When receiving provider acknowledged
     */
    private LocalDateTime acknowledgedAt;

    /**
     * Acknowledgement notes from receiving provider
     */
    @Column(length = 1000)
    private String acknowledgementNotes;

    /**
     * Scheduled appointment date/time
     */
    private LocalDateTime scheduledAppointmentAt;

    /**
     * Appointment location
     */
    @Column(length = 300)
    private String appointmentLocation;

    /**
     * The appointment this referral produced, when one could be created.
     *
     * <p>Null is a normal outcome, not a failure: a referral to an external
     * facility has no receiving provider or department, and an Appointment
     * cannot be built without both. Those referrals keep the timestamp and the
     * free-text location and nothing more.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id",
        foreignKey = @ForeignKey(name = "fk_referral_appointment"))
    private com.example.hms.model.Appointment appointment;

    /**
     * When the consultation/care actually began (status moved to IN_PROGRESS)
     */
    private LocalDateTime startedAt;

    /**
     * When referral was completed
     */
    private LocalDateTime completedAt;

    /**
     * Completion summary from receiving provider
     */
    @Column(length = 2000)
    private String completionSummary;

    /**
     * Follow-up recommendations
     */
    @Column(length = 1000)
    private String followUpRecommendations;

    /**
     * Reason for cancellation/rejection
     */
    @Column(length = 500)
    private String cancellationReason;

    /**
     * Insurance authorization number (if required)
     */
    @Column(length = 100)
    private String insuranceAuthNumber;

    /**
     * Priority score (system-calculated based on urgency + clinical factors)
     */
    private Integer priorityScore;

    /**
     * Additional metadata
     */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /**
     * Attachments (lab results, imaging, notes, etc.)
     */
    @OneToMany(mappedBy = "referral", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GeneralReferralAttachment> attachments = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA optimistic-lock version. Required for the SLA expiry sweep: without it,
     * a concurrent transaction that flips status (e.g. SUBMITTED → IN_PROGRESS)
     * between the sweep's SELECT and its UPDATE would be silently overwritten,
     * because the in-memory entity would still report the stale status. With
     * @Version, the second writer's flush throws ObjectOptimisticLockingFailureException
     * and the sweep skips the row.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // Business methods (state-machine guarded — illegal transitions throw IllegalStateException)
    //
    // DRAFT → SUBMITTED → ACKNOWLEDGED → SCHEDULED → IN_PROGRESS → COMPLETED
    // CANCELLED is reachable from any non-terminal status by the referring side.
    // REJECTED is reachable from SUBMITTED or ACKNOWLEDGED by the receiving side.
    // Terminal states (COMPLETED/CANCELLED/REJECTED/EXPIRED) admit no further transitions.

    public void submit() {
        requireStatus("submit", ReferralStatus.DRAFT);
        this.status = ReferralStatus.SUBMITTED;
        this.submittedAt = LocalDateTime.now();
        calculateSlaDueDate();
    }

    public void acknowledge(String notes, Staff receivingProvider) {
        requireStatus("acknowledge", ReferralStatus.SUBMITTED);
        this.status = ReferralStatus.ACKNOWLEDGED;
        this.acknowledgedAt = LocalDateTime.now();
        this.acknowledgementNotes = notes;
        this.receivingProvider = receivingProvider;
    }

    public void schedule(LocalDateTime appointmentTime, String location) {
        requireStatus("schedule", ReferralStatus.ACKNOWLEDGED);
        this.status = ReferralStatus.SCHEDULED;
        this.scheduledAppointmentAt = appointmentTime;
        this.appointmentLocation = location;
    }

    public void start() {
        requireStatus("start", ReferralStatus.ACKNOWLEDGED, ReferralStatus.SCHEDULED);
        this.status = ReferralStatus.IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
    }

    public void complete(String summary, String followUp) {
        // Complete is reachable from any post-acknowledgement non-terminal status:
        // some referrals never get a formal appointment slot (SCHEDULED) or explicit
        // "start" click (IN_PROGRESS) before being closed out.
        requireStatus("complete",
            ReferralStatus.ACKNOWLEDGED, ReferralStatus.SCHEDULED, ReferralStatus.IN_PROGRESS);
        this.status = ReferralStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.completionSummary = summary;
        this.followUpRecommendations = followUp;
    }

    public void cancel(String reason) {
        if (isTerminal(this.status)) {
            throw new IllegalStateException(
                "Cannot cancel referral in terminal status " + this.status);
        }
        this.status = ReferralStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    public void reject(String reason) {
        requireStatus("reject", ReferralStatus.SUBMITTED, ReferralStatus.ACKNOWLEDGED);
        this.status = ReferralStatus.REJECTED;
        this.cancellationReason = reason;
    }

    public void expire(String reason) {
        // IN_PROGRESS is intentionally excluded — once a consultation has actually begun,
        // it must terminate via complete() or cancel(), not by SLA expiry sweep.
        requireStatus("expire",
            ReferralStatus.SUBMITTED, ReferralStatus.ACKNOWLEDGED, ReferralStatus.SCHEDULED);
        this.status = ReferralStatus.EXPIRED;
        this.cancellationReason = reason;
    }

    private void requireStatus(String action, ReferralStatus... allowed) {
        for (ReferralStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw new IllegalStateException(
            "Cannot " + action + " referral in status " + this.status);
    }

    private static boolean isTerminal(ReferralStatus s) {
        return s == ReferralStatus.COMPLETED
            || s == ReferralStatus.CANCELLED
            || s == ReferralStatus.REJECTED
            || s == ReferralStatus.EXPIRED;
    }

    /**
     * Calculate SLA due date based on urgency
     */
    private void calculateSlaDueDate() {
        if (submittedAt != null && urgency != null) {
            switch (urgency) {
                case EMERGENCY -> this.slaDueAt = submittedAt.plusHours(2);
                case URGENT -> this.slaDueAt = submittedAt.plusHours(48);
                case PRIORITY -> this.slaDueAt = submittedAt.plusDays(7);
                case ROUTINE -> this.slaDueAt = submittedAt.plusDays(28);
            }
        }
    }

    /**
     * Add an attachment
     */
    public void addAttachment(GeneralReferralAttachment attachment) {
        if (this.attachments == null) {
            this.attachments = new ArrayList<>();
        }
        attachment.setReferral(this);
        this.attachments.add(attachment);
    }

    /**
     * Check if referral is overdue
     */
    public boolean isOverdue() {
        return slaDueAt != null && LocalDateTime.now().isAfter(slaDueAt)
               && !isTerminal(this.status);
    }
}
