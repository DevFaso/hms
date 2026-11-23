package com.example.hms.model;

import com.example.hms.enums.AntibodyWorkupStatus;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What a biologist recorded about a patient's red-cell antibody (V362, rule TRANSFUSION_POSITIVE_ANTIBODY_SCREEN):
 * the antibody identified, or the referral to a named centre with the decision to serve one request before the
 * answer. Append-only: a row is never updated, and none is ever removed by a later negative screen. Scoped to the
 * hospital that recorded it.
 */
@Entity
@Table(
    name = "patient_antibody_workups",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_antibody_workup_patient", columnList = "patient_id, hospital_id, recorded_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "antibodySpecificities", "decisionReason", "notes"})
public class PatientAntibodyWorkup extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_antibody_workup_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_antibody_workup_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AntibodyWorkupStatus status;

    /** The antibody's specificity ("anti-K, anti-E"), in the biologist's words: a clinical finding, so encrypted. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "antibody_specificities", columnDefinition = "TEXT")
    private String antibodySpecificities;

    /** The centre the sample is referred to (CNTS, a CRTS). */
    @Column(name = "referral_centre", length = 200)
    private String referralCentre;

    /** Why a request is served before the antibody is identified: a clinical narrative, so encrypted. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    /** The one request a referral decision serves. An id, not an association: the row outlives nothing it needs. */
    @Column(name = "transfusion_request_id")
    private UUID transfusionRequestId;

    @Column(name = "recorded_by_user_id", nullable = false)
    private UUID recordedByUserId;

    @Column(name = "recorded_by_display", length = 200)
    private String recordedByDisplay;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
