package com.example.hms.repository;

import com.example.hms.enums.ObgynReferralStatus;
import com.example.hms.model.referral.ObgynReferral;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.UUID;

public interface ObgynReferralRepository extends JpaRepository<ObgynReferral, UUID> {
    Page<ObgynReferral> findByPatient_Id(UUID patientId, Pageable pageable);
    /** E9 #59d — across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}). */
    Page<ObgynReferral> findByPatient_IdAndHospital_IdIn(UUID patientId, java.util.Collection<UUID> hospitalIds, Pageable pageable);

    /**
     * {@link #findByPatient_IdAndHospital_IdIn} less the referrals written in
     * a visit the acting hospital may not read
     * ({@code RecordAccessPolicy.withheldVisits}): a referral follows its
     * visit's restriction, in the query, so the page and its count agree.
     */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT r FROM ObgynReferral r
        WHERE r.patient.id = :patientId AND r.hospital.id IN :hospitalIds
          AND (r.encounter IS NULL OR r.encounter.id NOT IN :withheldVisitIds)
        """, countQuery = """
        SELECT COUNT(r) FROM ObgynReferral r
        WHERE r.patient.id = :patientId AND r.hospital.id IN :hospitalIds
          AND (r.encounter IS NULL OR r.encounter.id NOT IN :withheldVisitIds)
        """)
    Page<ObgynReferral> findByPatientAcrossHospitalsOutsideVisits(
        @org.springframework.data.repository.query.Param("patientId") UUID patientId,
        @org.springframework.data.repository.query.Param("hospitalIds") java.util.Collection<UUID> hospitalIds,
        @org.springframework.data.repository.query.Param("withheldVisitIds") java.util.Collection<UUID> withheldVisitIds,
        Pageable pageable);
    Page<ObgynReferral> findByHospital_Id(UUID hospitalId, Pageable pageable);
    Page<ObgynReferral> findByObgyn_Id(UUID userId, Pageable pageable);

    long countByStatus(ObgynReferralStatus status);
    long countByStatusAndSlaDueAtBefore(ObgynReferralStatus status, LocalDateTime threshold);
}
