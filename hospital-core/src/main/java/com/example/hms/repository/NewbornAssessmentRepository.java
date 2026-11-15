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

    /**
     * Provisional rule (user decision 2026-11-14, pending clinical approval): whether any assessment of this baby
     * recorded a sign of life (an Apgar score above 0, a heart rate or a respiratory rate). A stillborn outcome that
     * meets one is a conflict to assess, never a reason to silence the neonatal alerts.
     */
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(a) > 0 FROM NewbornAssessment a WHERE a.patient.id = :patientId"
        + " AND (a.apgarOneMinute > 0 OR a.apgarFiveMinute > 0 OR a.apgarTenMinute > 0"
        + " OR a.heartRateBpm > 0 OR a.respirationsPerMin > 0)")
    boolean anySignOfLifeRecorded(@org.springframework.data.repository.query.Param("patientId") UUID patientId);

    /**
     * Rex on #1152: whether a neonatal assessment of this delivery recorded on the MOTHER's chart (a baby the delivery
     * did not register, so it cannot be told apart) showed a sign of life. Like the delivery's own Apgar, it belongs to
     * no baby in particular: it makes a delivery with no live-born baby uncertain.
     */
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(a) > 0 FROM NewbornAssessment a"
        + " WHERE a.deliveryRecord.id = :deliveryId AND a.patient.id = a.deliveryRecord.patient.id"
        + " AND (a.apgarOneMinute > 0 OR a.apgarFiveMinute > 0 OR a.apgarTenMinute > 0"
        + " OR a.heartRateBpm > 0 OR a.respirationsPerMin > 0)")
    boolean anyUnattributedSignOfLifeRecorded(
        @org.springframework.data.repository.query.Param("deliveryId") UUID deliveryId);
}
