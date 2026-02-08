package com.example.hms.model.referral;

import com.example.hms.enums.ObgynReferralCareContext;
import com.example.hms.enums.ObgynReferralStatus;
import com.example.hms.enums.ObgynReferralUrgency;
import com.example.hms.enums.ObgynTransferType;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(
    name = "obgyn_referrals",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_obgyn_referral_patient", columnList = "patient_id"),
        @Index(name = "idx_obgyn_referral_status", columnList = "status"),
        @Index(name = "idx_obgyn_referral_sla", columnList = "sla_due_at"),
        @Index(name = "idx_obgyn_referral_obgyn", columnList = "obgyn_user_id"),
        @Index(name = "idx_obgyn_referral_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ObgynReferral extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, foreignKey = @ForeignKey(name = "fk_obgyn_referral_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false, foreignKey = @ForeignKey(name = "fk_obgyn_referral_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "midwife_user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_obgyn_referral_midwife"))
    private User midwife;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "obgyn_user_id", foreignKey = @ForeignKey(name = "fk_obgyn_referral_obgyn"))
    private User obgyn;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "encounter_id", foreignKey = @ForeignKey(name = "fk_obgyn_referral_encounter"))
        private Encounter encounter;

    @Column(name = "gestational_age_weeks")
    private Integer gestationalAgeWeeks;

    @Enumerated(EnumType.STRING)
    @Column(name = "care_context", nullable = false, length = 32)
    private ObgynReferralCareContext careContext;

    @Lob
    @Column(name = "referral_reason", nullable = false, columnDefinition = "TEXT")
    private String referralReason;

    @Lob
    @Column(name = "clinical_indication", columnDefinition = "TEXT")
    private String clinicalIndication;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", nullable = false, length = 32)
    private ObgynReferralUrgency urgency;

    @Lob
    @Column(name = "history_summary", columnDefinition = "TEXT")
    private String historySummary;

    @Builder.Default
    @Column(name = "ongoing_midwifery_care", nullable = false)
    private boolean ongoingMidwiferyCare = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", nullable = false, length = 32)
    private ObgynTransferType transferType;

    @Builder.Default
    @Column(name = "attachments_present", nullable = false)
    private boolean attachmentsPresent = false;

    @Column(name = "acknowledgement_timestamp")
    private LocalDateTime acknowledgementTimestamp;

    @Lob
    @Column(name = "plan_summary", columnDefinition = "TEXT")
    private String planSummary;

    @Column(name = "completion_timestamp")
    private LocalDateTime completionTimestamp;

    @Column(name = "cancelled_timestamp")
    private LocalDateTime cancelledTimestamp;

    @Lob
    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ObgynReferralStatus status;

    @Column(name = "sla_due_at")
    private LocalDateTime slaDueAt;

    @Column(name = "care_team_updated_at")
    private LocalDateTime careTeamUpdatedAt;

    @Column(name = "letter_storage_path", length = 512)
    private String letterStoragePath;

    @Column(name = "letter_generated_at")
    private LocalDateTime letterGeneratedAt;

    @Lob
    @Column(name = "patient_contact_snapshot", columnDefinition = "TEXT")
    private String patientContactSnapshot;

    @Lob
    @Column(name = "midwife_contact_snapshot", columnDefinition = "TEXT")
    private String midwifeContactSnapshot;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Builder.Default
    @OneToMany(mappedBy = "referral", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private Set<ReferralAttachment> attachments = new LinkedHashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "referral", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sentAt ASC")
    private Set<ObgynReferralMessage> messages = new LinkedHashSet<>();

    public void addAttachment(ReferralAttachment attachment) {
        attachments.add(attachment);
        attachment.setReferral(this);
        attachmentsPresent = !attachments.isEmpty();
    }

    public void addMessage(ObgynReferralMessage message) {
        messages.add(message);
        message.setReferral(this);
    }
}
