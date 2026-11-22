package com.example.hms.repository;

import com.example.hms.enums.RuleSignoffStatus;
import com.example.hms.model.HospitalRuleSignoff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V356: a hospital's sign-offs of the provisional rule sets. Every read names the hospital: one hospital's
 * decisions are never read for another.
 */
public interface HospitalRuleSignoffRepository extends JpaRepository<HospitalRuleSignoff, UUID> {

    /** Every sign-off of one hospital, newest first: the screen's statuses and the history. */
    List<HospitalRuleSignoff> findByHospitalIdOrderByRecordedAtDesc(UUID hospitalId);

    /** The live sign-off of one rule set at one version, if any (at most one, uq_rule_signoff_signed). */
    Optional<HospitalRuleSignoff> findFirstByHospitalIdAndRuleKeyAndRuleVersionAndStatusOrderByRecordedAtDesc(
        UUID hospitalId, String ruleKey, String ruleVersion, RuleSignoffStatus status);

    /** One sign-off of one hospital (the revoked row read back for its audit). */
    Optional<HospitalRuleSignoff> findByIdAndHospitalId(UUID id, UUID hospitalId);

    /**
     * Revokes one sign-off of one hospital only while it is still SIGNED: 1 when this call revoked it, 0 when it
     * was already revoked (a concurrent revocation won) or is not this hospital's.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE HospitalRuleSignoff s SET s.status = com.example.hms.enums.RuleSignoffStatus.REVOKED,"
        + " s.revokedAt = :at, s.revokedByUserId = :by, s.revokeReason = :reason, s.updatedAt = :at"
        + " WHERE s.id = :id AND s.hospitalId = :hospitalId"
        + " AND s.status = com.example.hms.enums.RuleSignoffStatus.SIGNED")
    int revokeIfSigned(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId, @Param("at") LocalDateTime at,
                       @Param("by") UUID revokedByUserId, @Param("reason") String reason);
}
