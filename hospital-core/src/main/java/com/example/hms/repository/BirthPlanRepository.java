package com.example.hms.repository;

import com.example.hms.model.BirthPlan;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for Birth Plan entity with custom query methods.
 */
@Repository
public interface BirthPlanRepository extends JpaRepository<BirthPlan, UUID> {

    /**
     * Find all birth plans for a specific patient.
     */
    List<BirthPlan> findByPatientOrderByCreatedAtDesc(Patient patient);

    /**
     * Find all birth plans for a specific patient by patient ID.
     */
    @Query("SELECT bp FROM BirthPlan bp WHERE bp.patient.id = :patientId ORDER BY bp.createdAt DESC")
    List<BirthPlan> findByPatientIdOrderByCreatedAtDesc(@Param("patientId") UUID patientId);

    /**
     * Find the most recent (active) birth plan for a patient.
     */
    @Query("""
        SELECT bp FROM BirthPlan bp 
        WHERE bp.patient.id = :patientId 
        ORDER BY bp.createdAt DESC
        """)
    Optional<BirthPlan> findActiveBirthPlanByPatientId(@Param("patientId") UUID patientId);

    /**
     * Find birth plans by hospital.
     */
    Page<BirthPlan> findByHospitalOrderByCreatedAtDesc(Hospital hospital, Pageable pageable);

    /**
     * Find birth plans by hospital ID.
     */
    @Query("SELECT bp FROM BirthPlan bp WHERE bp.hospital.id = :hospitalId ORDER BY bp.createdAt DESC")
    Page<BirthPlan> findByHospitalIdOrderByCreatedAtDesc(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    /**
     * Search birth plans with filters.
     */
    @Query("""
        SELECT bp FROM BirthPlan bp
        WHERE (:hospitalId IS NULL OR bp.hospital.id = :hospitalId)
          AND (:patientId IS NULL OR bp.patient.id = :patientId)
          AND (:providerReviewed IS NULL OR bp.providerReviewed = :providerReviewed)
          AND (:dueDateFrom IS NULL OR bp.expectedDueDate >= :dueDateFrom)
          AND (:dueDateTo IS NULL OR bp.expectedDueDate <= :dueDateTo)
        ORDER BY bp.createdAt DESC
        """)
    Page<BirthPlan> searchBirthPlans(
        @Param("hospitalId") UUID hospitalId,
        @Param("patientId") UUID patientId,
        @Param("providerReviewed") Boolean providerReviewed,
        @Param("dueDateFrom") LocalDate dueDateFrom,
        @Param("dueDateTo") LocalDate dueDateTo,
        Pageable pageable
    );

    /**
     * Find birth plans pending provider review.
     */
    @Query("""
        SELECT bp FROM BirthPlan bp
        WHERE bp.hospital.id = :hospitalId
          AND bp.providerReviewRequired = true
          AND (bp.providerReviewed IS NULL OR bp.providerReviewed = false)
        ORDER BY bp.createdAt DESC
        """)
    Page<BirthPlan> findPendingReviewByHospital(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    /**
     * Count birth plans for a patient.
     */
    long countByPatient(Patient patient);

    /**
     * Count birth plans pending review for a hospital.
     */
    @Query("""
        SELECT COUNT(bp) FROM BirthPlan bp
        WHERE bp.hospital.id = :hospitalId
          AND bp.providerReviewRequired = true
          AND (bp.providerReviewed IS NULL OR bp.providerReviewed = false)
        """)
    long countPendingReviewByHospital(@Param("hospitalId") UUID hospitalId);
}
