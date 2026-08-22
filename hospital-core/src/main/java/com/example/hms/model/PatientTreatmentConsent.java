package com.example.hms.model;

import com.example.hms.enums.TreatmentConsentMethod;
import com.example.hms.enums.TreatmentConsentSource;
import com.example.hms.enums.TreatmentConsentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
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
 * One consent-to-treat record (P3 #21).
 *
 * <p>The first such record in the system: the portal pre-check-in form
 * forced patients to tick a consent checkbox whose value the backend then
 * discarded, and the check-in dialog's attestations survived only inside an
 * audit-log STRING. This table follows the advance-directives
 * revoke-never-delete shape, with the V125 idiom of a SHA-256 digest as
 * tamper evidence when the consent is captured electronically.
 *
 * <p>Deliberately a RECORD, not a gate: check-in proceeds whether or not a
 * consent row exists — refusing treatment for a missing signature is a
 * clinical-workflow decision this table does not make.
 */
@Entity
@Table(
    name = "patient_treatment_consents",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_ptc_patient",  columnList = "patient_id"),
        @Index(name = "idx_ptc_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient", "hospital", "appointment", "encounter", "recordedByStaff", "recordedBy"})
public class PatientTreatmentConsent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_ptc_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_ptc_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id",
        foreignKey = @ForeignKey(name = "fk_ptc_appointment"))
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id",
        foreignKey = @ForeignKey(name = "fk_ptc_encounter"))
    private Encounter encounter;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TreatmentConsentStatus status = TreatmentConsentStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private TreatmentConsentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private TreatmentConsentSource source;

    /** The name as signed/typed — server-resolved for portal captures. */
    @Size(max = 200)
    @Column(name = "signed_name", length = 200)
    private String signedName;

    /** SHA-256 over a canonical payload; tamper evidence for ELECTRONIC captures. */
    @Column(name = "signature_hash", length = 64)
    private String signatureHash;

    @Column(name = "consented_at", nullable = false)
    private LocalDateTime consentedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /* ── Attribution ───────────────────────────────────────────────────── */

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_staff_id",
        foreignKey = @ForeignKey(name = "fk_ptc_staff"))
    private Staff recordedByStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_user_id",
        foreignKey = @ForeignKey(name = "fk_ptc_recorder"))
    private User recordedBy;

    /* ── Revocation (never delete) ─────────────────────────────────────── */

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by_user_id")
    private UUID revokedByUserId;

    @Size(max = 500)
    @Column(name = "revocation_reason", length = 500)
    private String revocationReason;

    @Size(max = 500)
    @Column(name = "notes", length = 500)
    private String notes;

    @PrePersist
    private void normalize() {
        if (status == null) {
            status = TreatmentConsentStatus.ACTIVE;
        }
        if (consentedAt == null) {
            consentedAt = LocalDateTime.now();
        }
    }
}
