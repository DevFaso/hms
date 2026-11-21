package com.example.hms.repository;

import com.example.hms.model.Payer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing-money plan PR 6 (V305): a hospital's payers. Every read and write names the hospital: a payer of another
 * hospital answers exactly as a missing one. The one unscoped read, {@link #findAllByOrderByCodeAsc}, serves only the
 * verified super-admin's global view (read-only).
 */
public interface PayerRepository extends JpaRepository<Payer, UUID> {

    Optional<Payer> findByIdAndHospitalId(UUID id, UUID hospitalId);

    List<Payer> findByHospitalIdOrderByCodeAsc(UUID hospitalId);

    /** Global view only (verified super-admin, no hospital named): every hospital's payers, read-only. */
    List<Payer> findAllByOrderByCodeAsc();

    boolean existsByHospitalIdAndCode(UUID hospitalId, String code);

    /** QA r65 D2731 (onboarding checklist): how many active payers ONE hospital has. */
    @Query("SELECT COUNT(p) FROM Payer p WHERE p.hospitalId = :hospitalId AND p.active = true")
    long countByHospitalIdAndActiveTrue(@Param("hospitalId") UUID hospitalId);

    /**
     * The payer, locked (SELECT ... FOR UPDATE) and only at its own hospital. Every change to a payer, its plans or
     * their rules takes this lock first, so two concurrent changes to one payer's catalogue are serialised.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payer p WHERE p.id = :id AND p.hospitalId = :hospitalId")
    Optional<Payer> lockByIdAtHospital(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId);
}
