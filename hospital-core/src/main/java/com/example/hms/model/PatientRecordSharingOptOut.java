package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A patient's decision to keep their record out of cross-hospital reads
 * (E8 #52). Their own hospital's access is untouched; only the treatment
 * presumption across hospitals is withdrawn.
 *
 * <p>One row per opt-out episode: {@code revokedAt} null means the opt-out is
 * in force. Revoking sets {@code revokedAt} rather than deleting, so the
 * disclosure report (#39) can show that a period of exclusion existed and who
 * ended it. The policy honours this row inside the predicate itself — it is
 * never bolted onto individual callers.
 */
@Entity
@Table(
    name = "patient_record_sharing_optouts",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_prso_patient_active", columnList = "patient_id, revoked_at")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientRecordSharingOptOut extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(name = "opted_out_at", nullable = false)
    private LocalDateTime optedOutAt;

    /** Free text, patient's own words or the registrar's note. Never required. */
    @Column(name = "reason", length = 1000)
    private String reason;

    /** The user who recorded it — the patient themselves, or the registrar acting for them. */
    @Column(name = "recorded_by_user_id")
    private UUID recordedByUserId;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by_user_id")
    private UUID revokedByUserId;

    public boolean isInForce() {
        return revokedAt == null;
    }
}
