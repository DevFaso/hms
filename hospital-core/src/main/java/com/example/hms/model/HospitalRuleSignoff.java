package com.example.hms.model;

import com.example.hms.enums.RuleSignoffStatus;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V356 (owner direction 2026-11-21): one hospital's sign-off of one provisional rule set
 * ({@code ClinicalRuleRegistry}) at one version.
 *
 * <p>The signer is usually the médecin-chef or the role the registry names, and usually not a user of the
 * platform: their name and title are recorded as written by the administrator who enters the sign-off
 * ({@link #recordedByUserId}); {@link #signerUserId} is set only when the signer is a user. A revoked row is kept,
 * with who revoked it and why; signing again adds a new row. Ids only towards the rest of the schema.
 *
 * <p>Recording a sign-off changes no rule's behaviour: only the « provisoire » label and this record.
 */
@Entity
@Table(name = "hospital_rule_signoffs", schema = "hospital",
    indexes = @Index(name = "idx_rule_signoff_hospital_rule", columnList = "hospital_id, rule_key, recorded_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class HospitalRuleSignoff extends BaseEntity {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    /** A {@code ClinicalRuleSetKey} name. */
    @Column(name = "rule_key", nullable = false, length = 64, updatable = false)
    private String ruleKey;

    /** The registry version the approver signed; a later version reads as pending again. */
    @Column(name = "rule_version", nullable = false, length = 64, updatable = false)
    private String ruleVersion;

    /** SIGNED or REVOKED; PENDING is never stored. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RuleSignoffStatus status;

    @Column(name = "signer_name", nullable = false, length = 200, updatable = false)
    private String signerName;

    /** The signer's role or title, as printed on the label (« Médecin-chef »). */
    @Column(name = "signer_title", nullable = false, length = 200, updatable = false)
    private String signerTitle;

    /** The signer's account when they have one at this hospital; usually null. */
    @Column(name = "signer_user_id", updatable = false)
    private UUID signerUserId;

    /** The date the approver signed (on paper, usually), not the date it was entered. */
    @Column(name = "signed_on", nullable = false, updatable = false)
    private LocalDate signedOn;

    @Column(name = "note", length = 1000, updatable = false)
    private String note;

    @Column(name = "recorded_by_user_id", nullable = false, updatable = false)
    private UUID recordedByUserId;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by_user_id")
    private UUID revokedByUserId;

    @Column(name = "revoke_reason", length = 1000)
    private String revokeReason;
}
