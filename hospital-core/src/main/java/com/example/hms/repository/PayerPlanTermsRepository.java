package com.example.hms.repository;

import com.example.hms.model.PayerPlanTerms;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Billing-money plan PR 6 (V305, review of #1202): the dated terms of a hospital's payer plans. Insert-only apart from
 * the service closing {@code effective_to}; nothing deletes one. {@link #findByPlanIds} reads the terms of plans
 * already read at their hospital (or in the super-admin's global view).
 */
public interface PayerPlanTermsRepository extends JpaRepository<PayerPlanTerms, UUID> {

    /** A plan's terms at its own hospital, oldest start first (read under the payer's lock before a change). */
    List<PayerPlanTerms> findByPlanIdAndHospitalIdOrderByEffectiveFromAscCreatedAtAsc(UUID planId, UUID hospitalId);

    @Query("SELECT t FROM PayerPlanTerms t WHERE t.planId IN :planIds ORDER BY t.effectiveFrom ASC, t.createdAt ASC")
    List<PayerPlanTerms> findByPlanIds(@Param("planIds") Collection<UUID> planIds);
}
