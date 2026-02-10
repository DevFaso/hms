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

    Optional<NewbornAssessment> findFirstByPatient_IdOrderByAssessmentTimeDesc(UUID patientId);

    Optional<NewbornAssessment> findByIdAndPatient_IdAndHospital_Id(UUID id, UUID patientId, UUID hospitalId);

    @Query("""
        select a from NewbornAssessment a
        where a.patient.id = :patientId
          and (:hospitalId is null or a.hospital.id = :hospitalId)
          and (:from is null or a.assessmentTime >= :from)
          and (:to is null or a.assessmentTime <= :to)
        order by a.assessmentTime desc
    """)
    List<NewbornAssessment> findWithinRange(
        @Param("patientId") UUID patientId,
        @Param("hospitalId") UUID hospitalId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        Pageable pageable
    );
}
