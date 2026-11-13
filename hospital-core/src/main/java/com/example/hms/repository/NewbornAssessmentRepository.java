package com.example.hms.repository;

import com.example.hms.model.neonatal.NewbornAssessment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NewbornAssessmentRepository extends JpaRepository<NewbornAssessment, UUID> {

    List<NewbornAssessment> findByPatient_IdAndHospital_IdOrderByAssessmentTimeDesc(
        UUID patientId,
        UUID hospitalId,
        Pageable pageable
    );

    /**
     * E9 #59d — across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}).
     * QA D1592: on the mother's chart, her deliveries' babies' assessments too
     * (recorded on each baby's own chart since the baby is a patient).
     */
    @Query("""
        select a from NewbornAssessment a left join a.deliveryRecord d
        where (a.patient.id = :patientId or d.patient.id = :patientId)
          and a.hospital.id in :hospitalIds
        order by a.assessmentTime desc
    """)
    List<NewbornAssessment> findByPatient_IdAndHospital_IdInOrderByAssessmentTimeDesc(
        @Param("patientId") UUID patientId, @Param("hospitalIds") java.util.Collection<UUID> hospitalIds,
        Pageable pageable);

    Optional<NewbornAssessment> findFirstByPatient_IdOrderByAssessmentTimeDesc(UUID patientId);

    /** QA D1424: the last heart rate on the chart at or before this assessment; a row that did not measure one is skipped. */
    Optional<NewbornAssessment> findFirstByPatient_IdAndHospital_IdAndHeartRateBpmIsNotNullAndAssessmentTimeLessThanEqualOrderByAssessmentTimeDesc(
        UUID patientId, UUID hospitalId, LocalDateTime assessmentTime);

    /** Earliest assessment with a delivery back-link — the growth chart's birth-weight seed. */
    Optional<NewbornAssessment> findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(UUID patientId);

    /** QA D1564: the same, at one hospital - another hospital's earlier row never hides this one's. */
    Optional<NewbornAssessment> findFirstByPatient_IdAndHospital_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(
        UUID patientId, UUID hospitalId);

    Optional<NewbornAssessment> findByIdAndPatient_IdAndHospital_Id(UUID id, UUID patientId, UUID hospitalId);

    @Query("""
        select a from NewbornAssessment a left join a.deliveryRecord d
        where (a.patient.id = :patientId or d.patient.id = :patientId)
          and (:hospitalId is null or a.hospital.id = :hospitalId)
          and (cast(:from as LocalDateTime) is null or a.assessmentTime >= :from)
          and (cast(:to as LocalDateTime) is null or a.assessmentTime <= :to)
        order by a.assessmentTime desc
    """)
    List<NewbornAssessment> findWithinRange(
        @Param("patientId") UUID patientId,
        @Param("hospitalId") UUID hospitalId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        Pageable pageable
    );

    /** QA D658: the baby's assessments after the one that raised an alert, newest first. */
    List<NewbornAssessment> findByPatient_IdAndAssessmentTimeAfterOrderByAssessmentTimeDesc(
        UUID patientId, java.time.LocalDateTime after);
}
