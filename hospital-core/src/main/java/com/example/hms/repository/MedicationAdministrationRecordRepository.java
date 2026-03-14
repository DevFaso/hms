package com.example.hms.repository;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.model.medication.MedicationAdministrationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface MedicationAdministrationRecordRepository extends JpaRepository<MedicationAdministrationRecord, UUID> {

    List<MedicationAdministrationRecord> findByPatient_IdAndHospital_IdAndStatusAndScheduledTimeBetween(
        UUID patientId,
        UUID hospitalId,
        MedicationAdministrationStatus status,
        LocalDateTime from,
        LocalDateTime to
    );

    @Query("""
        SELECT m FROM MedicationAdministrationRecord m
        WHERE m.hospital.id = :hospitalId
          AND m.status = :status
          AND m.scheduledTime <= :cutoff
        ORDER BY m.scheduledTime ASC
    """)
    List<MedicationAdministrationRecord> findDueForHospital(
        @Param("hospitalId") UUID hospitalId,
        @Param("status") MedicationAdministrationStatus status,
        @Param("cutoff") LocalDateTime cutoff
    );

    List<MedicationAdministrationRecord> findByHospital_IdAndStatusOrderByScheduledTimeAsc(
        UUID hospitalId,
        MedicationAdministrationStatus status
    );

    long countByHospital_IdAndStatusAndScheduledTimeBefore(
        UUID hospitalId,
        MedicationAdministrationStatus status,
        LocalDateTime cutoff
    );
}
