package com.example.hms.repository;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.model.MedicationAdministrationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface MedicationAdministrationRecordRepository extends JpaRepository<MedicationAdministrationRecord, UUID> {

    List<MedicationAdministrationRecord> findByPatient_IdAndHospital_IdAndStatus(
        UUID patientId, UUID hospitalId, MedicationAdministrationStatus status);

    @Query("""
        SELECT m FROM MedicationAdministrationRecord m
        JOIN FETCH m.prescription
        JOIN FETCH m.patient
        WHERE m.hospital.id = :hospitalId
          AND m.status = :status
          AND m.scheduledTime BETWEEN :from AND :to
        ORDER BY m.scheduledTime ASC
    """)
    List<MedicationAdministrationRecord> findDueForHospital(
        @Param("hospitalId") UUID hospitalId,
        @Param("status") MedicationAdministrationStatus status,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to);

    @Query("""
        SELECT m FROM MedicationAdministrationRecord m
        JOIN FETCH m.prescription
        JOIN FETCH m.patient
        WHERE m.hospital.id = :hospitalId
          AND m.administeredByStaff.id = :staffId
          AND m.status = :status
          AND m.scheduledTime BETWEEN :from AND :to
        ORDER BY m.scheduledTime ASC
    """)
    List<MedicationAdministrationRecord> findDueForNurse(
        @Param("hospitalId") UUID hospitalId,
        @Param("staffId") UUID staffId,
        @Param("status") MedicationAdministrationStatus status,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to);

    long countByHospital_IdAndStatusAndScheduledTimeBefore(
        UUID hospitalId, MedicationAdministrationStatus status, LocalDateTime before);
}
