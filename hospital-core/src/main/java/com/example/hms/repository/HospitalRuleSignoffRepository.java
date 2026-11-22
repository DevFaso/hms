package com.example.hms.repository;

import com.example.hms.enums.RuleSignoffStatus;
import com.example.hms.model.HospitalRuleSignoff;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
