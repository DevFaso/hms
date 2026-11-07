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

    /**
     * Every MAR row of one prescription, latest scheduled dose first (D77 dose
     * schedule). QA D1409: two doses of an as-needed order given in the same
     * minute share their slot, and nothing broke the tie, so the "last dose"
     * could be the earlier one. The dose recorded last comes first, then a
     * total order; a PENDING row, recorded by nobody yet, follows its slot's
     * recorded rows.
     */
    @Query("""
        SELECT m FROM MedicationAdministrationRecord m
        WHERE m.prescription.id = :prescriptionId
        ORDER BY m.scheduledTime DESC, m.administeredAt DESC NULLS LAST, m.createdAt DESC, m.id DESC
    """)
    List<MedicationAdministrationRecord> findByPrescription_IdOrderByScheduledTimeDesc(
        @Param("prescriptionId") UUID prescriptionId);

    /**
     * QA D1314: {@link #findByPrescription_IdOrderByScheduledTimeDesc} for a
     * whole ward, in that order, with what a row's dose history names (the
     * nurse, the witness) in the same statement.
     */
    @Query("""
        SELECT m FROM MedicationAdministrationRecord m
        LEFT JOIN FETCH m.administeredByStaff s
        LEFT JOIN FETCH s.user su
        LEFT JOIN FETCH su.patientProfile
        LEFT JOIN FETCH m.witnessUser wu
        LEFT JOIN FETCH wu.patientProfile
        LEFT JOIN FETCH wu.staffProfile
        WHERE m.prescription.id IN :prescriptionIds
        ORDER BY m.scheduledTime DESC, m.administeredAt DESC NULLS LAST, m.createdAt DESC, m.id DESC
    """)
    List<MedicationAdministrationRecord> findByPrescriptionIdsNewestSlotFirst(
        @Param("prescriptionIds") java.util.Collection<UUID> prescriptionIds);

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

    /** Current status straight from the database (bypasses a stale managed entity). */
    @Query("SELECT m.status FROM MedicationAdministrationRecord m WHERE m.id = :id")
    java.util.Optional<MedicationAdministrationStatus> findStatusById(@Param("id") UUID id);

    long countByHospital_IdAndStatusAndScheduledTimeBefore(
        UUID hospitalId, MedicationAdministrationStatus status, LocalDateTime before);
}
