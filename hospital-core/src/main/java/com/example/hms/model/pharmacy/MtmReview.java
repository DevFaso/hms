package com.example.hms.model.pharmacy;

import com.example.hms.enums.MtmReviewStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
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
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
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

/**
 * P-09: Medication Therapy Management (MTM) review record.
 *
 * <p>Captures a pharmacist-led review of a patient's medication regimen for
 * chronic disease management, adherence counselling, and polypharmacy. Builds
 * on the existing retrospective timeline produced by
 * {@code MedicationHistoryServiceImpl} but turns it into an actionable
 * intervention record with audit trail.
 */
@Entity
@Table(
    name = "mtm_reviews",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_mtm_patient", columnList = "patient_id"),
        @Index(name = "idx_mtm_hospital", columnList = "hospital_id"),
        @Index(name = "idx_mtm_pharmacist", columnList = "pharmacist_user_id"),
        @Index(name = "idx_mtm_status", columnList = "status"),
        @Index(name = "idx_mtm_review_date", columnList = "review_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"patient", "hospital", "pharmacistUser"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class MtmReview extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_mtm_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_mtm_hospital"))
    private Hospital hospital;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pharmacist_user_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_mtm_pharmacist"))
    private User pharmacistUser;

    @NotNull
    @Column(name = "review_date", nullable = false)
    private LocalDateTime reviewDate;

    @Size(max = 500)
    @Column(name = "chronic_condition_focus", length = 500)
    private String chronicConditionFocus;

    @Column(name = "adherence_concern", nullable = false)
    @Builder.Default
    private boolean adherenceConcern = false;

    @Column(name = "polypharmacy_alert", nullable = false)
    @Builder.Default
    private boolean polypharmacyAlert = false;

    @Size(max = 2000)
    @Column(name = "intervention_summary", length = 2000)
    private String interventionSummary;

    @Size(max = 2000)
    @Column(name = "recommended_actions", length = 2000)
    private String recommendedActions;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MtmReviewStatus status = MtmReviewStatus.DRAFT;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;
}
