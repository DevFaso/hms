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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
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

    // Business methods

    /**
     * Submit referral
     */
    public void submit() {
        this.status = ReferralStatus.SUBMITTED;
        this.submittedAt = LocalDateTime.now();
        calculateSlaDueDate();
    }

    /**
     * Acknowledge referral
     */
    public void acknowledge(String notes, Staff receivingProvider) {
        this.status = ReferralStatus.ACKNOWLEDGED;
        this.acknowledgedAt = LocalDateTime.now();
        this.acknowledgementNotes = notes;
        this.receivingProvider = receivingProvider;
    }

    /**
     * Schedule appointment
     */
    public void schedule(LocalDateTime appointmentTime, String location) {
        this.status = ReferralStatus.SCHEDULED;
        this.scheduledAppointmentAt = appointmentTime;
        this.appointmentLocation = location;
    }

    /**
     * Complete referral
     */
    public void complete(String summary, String followUp) {
        this.status = ReferralStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.completionSummary = summary;
        this.followUpRecommendations = followUp;
    }

    /**
     * Cancel referral
     */
    public void cancel(String reason) {
        this.status = ReferralStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    /**
     * Reject referral
     */
    public void reject(String reason) {
        this.status = ReferralStatus.REJECTED;
        this.cancellationReason = reason;
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
               && status != ReferralStatus.COMPLETED && status != ReferralStatus.CANCELLED 
               && status != ReferralStatus.REJECTED;
    }
}
