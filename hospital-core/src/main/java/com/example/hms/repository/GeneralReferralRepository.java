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

    /**
     * Find referrals by referring provider
     */
    List<GeneralReferral> findByReferringProviderIdOrderByCreatedAtDesc(UUID referringProviderId);

    /**
     * Find referrals by receiving provider
     */
    List<GeneralReferral> findByReceivingProviderIdOrderByCreatedAtDesc(UUID receivingProviderId);

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
     * Find overdue referrals
     */
    @Query("SELECT r FROM GeneralReferral r WHERE r.slaDueAt < :now " +
           "AND r.status NOT IN ('COMPLETED', 'CANCELLED', 'REJECTED') ORDER BY r.slaDueAt ASC")
    List<GeneralReferral> findOverdueReferrals(@Param("now") LocalDateTime now);

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
}
