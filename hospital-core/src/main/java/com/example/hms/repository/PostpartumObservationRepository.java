package com.example.hms.repository;

import com.example.hms.model.postpartum.PostpartumObservation;
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
public interface PostpartumObservationRepository extends JpaRepository<PostpartumObservation, UUID> {

    List<PostpartumObservation> findByCarePlan_IdOrderByObservationTimeDesc(UUID carePlanId, Pageable pageable);

    List<PostpartumObservation> findByPatient_IdAndHospital_IdOrderByObservationTimeDesc(
        UUID patientId,
        UUID hospitalId,
        Pageable pageable
    );

    /** E9 #59d — across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}). */
    List<PostpartumObservation> findByPatient_IdAndHospital_IdInOrderByObservationTimeDesc(
        UUID patientId, java.util.Collection<UUID> hospitalIds, Pageable pageable);

    Optional<PostpartumObservation> findFirstByCarePlan_IdOrderByObservationTimeDesc(UUID carePlanId);

    /**
     * QA D268, CR-06 class: a haemorrhage is on this plan when a row carries a HEMORRHAGE alert (the
     * indicators) or a clinician attested the protocol. Either one keeps the 15-minute checks; a past
     * row holding a true keeps them too, whatever set it.
     */
    @Query("""
        select count(o) > 0 from PostpartumObservation o
        where o.carePlan.id = :carePlanId
          and (o.hemorrhageProtocolActivated = true
               or exists (select 1 from o.alerts a
                          where a.type = com.example.hms.enums.PostpartumAlertType.HEMORRHAGE))
    """)
    boolean existsHaemorrhageOnPlan(@Param("carePlanId") UUID carePlanId);

    Optional<PostpartumObservation> findByIdAndPatient_IdAndHospital_Id(UUID id, UUID patientId, UUID hospitalId);

    @Query("""
        select o from PostpartumObservation o
        where o.patient.id = :patientId
          and (:hospitalId is null or o.hospital.id = :hospitalId)
          and (cast(:from as LocalDateTime) is null or o.observationTime >= :from)
          and (cast(:to as LocalDateTime) is null or o.observationTime <= :to)
        order by o.observationTime desc
    """)
    List<PostpartumObservation> findWithinRange(
        @Param("patientId") UUID patientId,
        @Param("hospitalId") UUID hospitalId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        Pageable pageable
    );

    /** QA D658: the plan's observations after the one that raised an alert, newest first. */
    List<PostpartumObservation> findByCarePlan_IdAndObservationTimeAfterOrderByObservationTimeDesc(
        UUID carePlanId, java.time.LocalDateTime after);
}
