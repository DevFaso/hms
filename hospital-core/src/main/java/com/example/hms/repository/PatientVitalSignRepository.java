package com.example.hms.repository;

import com.example.hms.model.PatientVitalSign;
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
public interface PatientVitalSignRepository extends JpaRepository<PatientVitalSign, UUID> {

    Optional<PatientVitalSign> findFirstByPatient_IdOrderByRecordedAtDesc(UUID patientId);

    Optional<PatientVitalSign> findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(UUID patientId, UUID hospitalId);

    List<PatientVitalSign> findByPatient_IdOrderByRecordedAtDesc(UUID patientId, Pageable pageable);

    List<PatientVitalSign> findByPatient_IdAndHospital_IdOrderByRecordedAtDesc(UUID patientId, UUID hospitalId, Pageable pageable);

        @Query("""
                SELECT v FROM PatientVitalSign v
                WHERE v.patient.id = :patientId
                    AND (:hospitalId IS NULL OR v.hospital.id = :hospitalId)
                    AND v.recordedAt >= COALESCE(:from, v.recordedAt)
                    AND v.recordedAt <= COALESCE(:to, v.recordedAt)
                ORDER BY v.recordedAt DESC
        """)
    List<PatientVitalSign> findWithinRange(@Param("patientId") UUID patientId,
                                           @Param("hospitalId") UUID hospitalId,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           Pageable pageable);
}
