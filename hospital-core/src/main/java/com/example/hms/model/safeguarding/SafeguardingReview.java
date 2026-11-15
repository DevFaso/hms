package com.example.hms.model.safeguarding;

import com.example.hms.enums.SafeguardingReviewStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * r53, provisional rule (user decision 2026-11-14, pending the obstetric lead's and the child-protection lead's
 * approval, approvals register): the labour admission of a minor under the configured age opens a safeguarding
 * review. It never blocks or delays the admission or any care; it stays open until a clinician records the review.
 *
 * <p>Ids only, no names (V296). Written only by {@code SafeguardingReviewWorker} and
 * {@code SafeguardingReviewService}, read only through {@code SafeguardingReviewController}, by the treating team
 * and the administrators of the review's own hospital ({@code SafeguardingAccessGuardTest}).
 */
@Entity
@Table(
    name = "safeguarding_reviews",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_safeguarding_review_hospital_status", columnList = "hospital_id, status"),
        @Index(name = "idx_safeguarding_review_patient", columnList = "patient_id, hospital_id")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "reviewNote")
// Identity is the id, as for every BaseEntity row; adding columns adds no equality.
@SuppressWarnings("java:S2160")
public class SafeguardingReview extends BaseEntity {

    /** The one trigger today: a labour admission under the configured age. */
    public static final String TRIGGER_MINOR_LABOUR = "MINOR_LABOUR";
    public static final String SOURCE_ADMISSION = "ADMISSION";
    public static final String SOURCE_LABOR_EPISODE = "LABOR_EPISODE";

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "trigger_code", nullable = false, length = 40)
    private String triggerCode;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    /** Her age in full years at the admission. */
    @Column(name = "patient_age_years", nullable = false)
    private Integer patientAgeYears;

    /** The configured threshold the age was under when the review opened (provisional). */
    @Column(name = "age_threshold_years", nullable = false)
    private Integer ageThresholdYears;

    @Column(name = "raised_at", nullable = false)
    private LocalDateTime raisedAt;

    @Column(name = "raised_by_user_id")
    private UUID raisedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SafeguardingReviewStatus status;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by_user_id")
    private UUID reviewedByUserId;

    /** The reviewer's documented review: a clinical narrative, so encrypted. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;
}
