package com.example.hms.repository;

import com.example.hms.model.PayerPlanRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing-money plan PR 6 (V305): the rules of a hospital's payer plans. Insert-only apart from the service closing
 * {@code effective_to}; nothing deletes one. {@link #findByPlanIds} reads the rules of plans already read at their
 * hospital (or in the super-admin's global view).
 */
public interface PayerPlanRuleRepository extends JpaRepository<PayerPlanRule, UUID> {

    Optional<PayerPlanRule> findByIdAndPlanIdAndHospitalId(UUID id, UUID planId, UUID hospitalId);

    /** A plan's rules at its own hospital, oldest start first (read under the payer's lock before a change). */
    List<PayerPlanRule> findByPlanIdAndHospitalIdOrderByEffectiveFromAscCreatedAtAsc(UUID planId, UUID hospitalId);

    @Query("SELECT r FROM PayerPlanRule r WHERE r.planId IN :planIds ORDER BY r.effectiveFrom ASC, r.createdAt ASC")
    List<PayerPlanRule> findByPlanIds(@Param("planIds") Collection<UUID> planIds);
}
