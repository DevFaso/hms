package com.example.hms.model;

import com.example.hms.enums.ClaimRefusalClass;
import com.example.hms.enums.ClaimRefusalResolution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Insurer claims plan PR 3 (V458): one refusal by the payer of all or part of a bordereau line: the amount, the
 * class of its reason, the reason as the payer gave it, and what the hospital did with it. Insert-only: the history
 * of the line. Only a refusal for a reason on the patient's side can be re-billed to the patient (owner decision of
 * 2026-11-26).
 */
@Entity
@Table(
    name = "insurer_claim_refusals",
    schema = "billing",
    indexes = {
        @Index(name = "idx_claim_refusal_line", columnList = "claim_line_id"),
        @Index(name = "idx_claim_refusal_share", columnList = "share_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InsurerClaimRefusal extends BaseEntity {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "claim_line_id", nullable = false, updatable = false)
    private UUID claimLineId;

    @Column(name = "share_id", nullable = false, updatable = false)
    private UUID shareId;

    /** Whole francs, more than 0. Never updated. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_class", nullable = false, length = 20, updatable = false)
    private ClaimRefusalClass reasonClass;

    /** The payer's reason, as typed. */
    @Column(name = "reason", nullable = false, length = 500, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", nullable = false, length = 20, updatable = false)
    private ClaimRefusalResolution resolution;

    /** The write-off request this refusal opened, when its resolution is WRITE_OFF_REQUEST. */
    @Column(name = "write_off_id", updatable = false)
    private UUID writeOffId;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    /** A retried request carrying the same key answers with the refusal already recorded (unique per hospital). */
    @Column(name = "client_request_id", nullable = false, updatable = false)
    private UUID clientRequestId;
}
