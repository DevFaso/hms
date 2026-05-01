package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Short-lived emergency-access grant ("break-the-glass") that lets a
 * privileged clinician read a patient's chart without a pre-existing
 * {@link PatientConsent}. Every read served under a live session must
 * record a {@code BREAK_GLASS_ACCESS} audit event.
 *
 * <p>Sessions are time-bound (default 4 h, set by the service) and can
 * be revoked early. {@link #isLive()} captures both conditions; callers
 * MUST use it rather than re-implementing the predicate.
 */
@Entity
@Table(
    name = "break_glass_sessions",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_bg_sessions_user_patient_open", columnList = "user_id, patient_id"),
        @Index(name = "idx_bg_sessions_patient_open", columnList = "patient_id, expires_at"),
        @Index(name = "idx_bg_sessions_hospital_started", columnList = "hospital_id, started_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user", "patient", "hospital", "revokedBy"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class BreakGlassSession extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_bg_user"))
    private User user;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_bg_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_bg_hospital"))
    private Hospital hospital;

    @NotBlank
    @Size(max = 1024)
    @Column(name = "reason", nullable = false, length = 1024)
    private String reason;

    @NotNull
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by_user_id",
        foreignKey = @ForeignKey(name = "fk_bg_revoked_by"))
    private User revokedBy;

    @Size(max = 1024)
    @Column(name = "revoke_reason", length = 1024)
    private String revokeReason;

    /** Number of audited reads served under this session. Maintained by the service layer. */
    @Column(name = "audit_count", nullable = false)
    @Builder.Default
    private int auditCount = 0;

    /** True when the session is still active: not revoked and not expired. */
    public boolean isLive() {
        return isLiveAt(LocalDateTime.now());
    }

    /** Same as {@link #isLive()} but evaluated at a caller-supplied instant (testable). */
    public boolean isLiveAt(LocalDateTime moment) {
        return revokedAt == null && expiresAt.isAfter(moment);
    }
}
