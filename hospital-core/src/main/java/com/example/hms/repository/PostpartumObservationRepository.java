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

    Optional<PostpartumObservation> findFirstByCarePlan_IdOrderByObservationTimeDesc(UUID carePlanId);

    Optional<PostpartumObservation> findByIdAndPatient_IdAndHospital_Id(UUID id, UUID patientId, UUID hospitalId);

    @Query("""
        select o from PostpartumObservation o
        where o.patient.id = :patientId
          and (:hospitalId is null or o.hospital.id = :hospitalId)
          and (:from is null or o.observationTime >= :from)
          and (:to is null or o.observationTime <= :to)
        order by o.observationTime desc
    """)
    List<PostpartumObservation> findWithinRange(
        @Param("patientId") UUID patientId,
        @Param("hospitalId") UUID hospitalId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        Pageable pageable
    );
}
