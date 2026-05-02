package com.example.hms.repository;

import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.model.GeneralReferral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for GeneralReferral entity
 */
@Repository
public interface GeneralReferralRepository extends JpaRepository<GeneralReferral, UUID> {

    /**
     * Find referrals by patient
     */
    List<GeneralReferral> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    List<GeneralReferral> findByPatientIdAndHospitalIdOrderByCreatedAtDesc(UUID patientId, UUID hospitalId);

    /**
     * Find referrals by referring provider
     */
    List<GeneralReferral> findByReferringProviderIdOrderByCreatedAtDesc(UUID referringProviderId);

    List<GeneralReferral> findByReferringProviderIdAndHospitalIdOrderByCreatedAtDesc(UUID referringProviderId, UUID hospitalId);

    /**
     * Find referrals by receiving provider
     */
    List<GeneralReferral> findByReceivingProviderIdOrderByCreatedAtDesc(UUID receivingProviderId);

    List<GeneralReferral> findByReceivingProviderIdAndHospitalIdOrderByCreatedAtDesc(UUID receivingProviderId, UUID hospitalId);

    /**
     * Find referrals by hospital and status
     */
    List<GeneralReferral> findByHospitalIdAndStatusOrderByCreatedAtDesc(UUID hospitalId, ReferralStatus status);

       /**
        * Find all referrals for a hospital regardless of status
        */
       List<GeneralReferral> findByHospitalIdOrderByCreatedAtDesc(UUID hospitalId);

    List<GeneralReferral> findByStatusOrderByCreatedAtDesc(ReferralStatus status);

    List<GeneralReferral> findAllByOrderByCreatedAtDesc();

    /**
     * Find referrals by specialty
     */
    List<GeneralReferral> findByTargetSpecialtyAndStatusOrderByCreatedAtDesc(ReferralSpecialty specialty, ReferralStatus status);

    /**
     * Find referrals by urgency
     */
    List<GeneralReferral> findByUrgencyAndStatusOrderBySlaDueAtAsc(ReferralUrgency urgency, ReferralStatus status);

    /**
     * Find overdue referrals (any non-terminal status with slaDueAt in the past)
     */
    @Query("SELECT r FROM GeneralReferral r WHERE r.slaDueAt < :now " +
           "AND r.status NOT IN ('COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED') ORDER BY r.slaDueAt ASC")
    List<GeneralReferral> findOverdueReferrals(@Param("now") LocalDateTime now);

    @Query("SELECT r FROM GeneralReferral r WHERE r.hospital.id = :hospitalId AND r.slaDueAt < :now " +
           "AND r.status NOT IN ('COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED') ORDER BY r.slaDueAt ASC")
    List<GeneralReferral> findOverdueReferralsByHospital(@Param("hospitalId") UUID hospitalId, @Param("now") LocalDateTime now);

    /**
     * Find referrals eligible for the EXPIRED auto-sweep:
     * post-submission, pre-consultation statuses with slaDueAt before the cutoff.
     * IN_PROGRESS is intentionally excluded — once a consultation has actually begun,
     * it must terminate via complete() or cancel(), not by an SLA sweep.
     *
     * <p>Unscoped variant — used by the {@code @Scheduled} sweep (system actor)
     * and by SUPER_ADMIN-driven global runs from the admin endpoint.
     */
    @Query("SELECT r FROM GeneralReferral r WHERE r.slaDueAt < :cutoff " +
           "AND r.status IN ('SUBMITTED', 'ACKNOWLEDGED', 'SCHEDULED') ORDER BY r.slaDueAt ASC")
    List<GeneralReferral> findExpirableReferrals(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Hospital-scoped counterpart for the manual admin endpoint. A
     * ROLE_HOSPITAL_ADMIN must only sweep their own hospital, so the
     * controller resolves the active hospital and the service routes to
     * this query instead of the unscoped one.
     */
    @Query("SELECT r FROM GeneralReferral r WHERE r.hospital.id = :hospitalId " +
           "AND r.slaDueAt < :cutoff " +
           "AND r.status IN ('SUBMITTED', 'ACKNOWLEDGED', 'SCHEDULED') ORDER BY r.slaDueAt ASC")
    List<GeneralReferral> findExpirableReferralsByHospital(
        @Param("hospitalId") UUID hospitalId,
        @Param("cutoff") LocalDateTime cutoff);

    /**
     * Find pending referrals for provider
     */
    @Query("SELECT r FROM GeneralReferral r WHERE r.receivingProvider.id = :providerId " +
           "AND r.status IN ('SUBMITTED', 'ACKNOWLEDGED', 'SCHEDULED') ORDER BY r.urgency DESC, r.submittedAt ASC")
    List<GeneralReferral> findPendingForProvider(@Param("providerId") UUID providerId);

    /**
     * Count referrals by status and date range
     */
    @Query("SELECT COUNT(r) FROM GeneralReferral r WHERE r.hospital.id = :hospitalId " +
           "AND r.status = :status AND r.createdAt BETWEEN :startDate AND :endDate")
    Long countByStatusAndDateRange(
        @Param("hospitalId") UUID hospitalId,
        @Param("status") ReferralStatus status,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    /**
     * Find referrals by department
     */
    List<GeneralReferral> findByTargetDepartmentIdOrderByCreatedAtDesc(UUID departmentId);

    /**
     * Find referrals received by a hospital (the hospital is the destination/receiving hospital)
     */
    List<GeneralReferral> findByReceivingHospitalIdOrderByCreatedAtDesc(UUID receivingHospitalId);

    List<GeneralReferral> findByReceivingHospitalIdAndStatusOrderByCreatedAtDesc(UUID receivingHospitalId, ReferralStatus status);
}
