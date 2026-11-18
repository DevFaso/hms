package com.example.hms.repository;

import com.example.hms.model.PayerPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing-money plan PR 6 (V305): the plans ("formules") of a hospital's payers. Every read names the hospital: a plan
 * of another hospital answers exactly as a missing one, which is how a patient's coverage is refused a plan of
 * another hospital. {@link #findByPayerIdInOrderByCodeAsc} reads the plans of payers already read at their
 * hospital (or in the super-admin's global view).
 */
public interface PayerPlanRepository extends JpaRepository<PayerPlan, UUID> {

    Optional<PayerPlan> findByIdAndHospitalId(UUID id, UUID hospitalId);

    Optional<PayerPlan> findByIdAndPayerIdAndHospitalId(UUID id, UUID payerId, UUID hospitalId);

    /** The plans of payers the caller has already read (at their hospital, or in the global view). */
    List<PayerPlan> findByPayerIdInOrderByCodeAsc(Collection<UUID> payerIds);

    boolean existsByPayerIdAndCode(UUID payerId, String code);
}
