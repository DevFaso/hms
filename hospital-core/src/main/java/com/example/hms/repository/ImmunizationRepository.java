package com.example.hms.repository;

import com.example.hms.model.PatientImmunization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ImmunizationRepository extends JpaRepository<PatientImmunization, UUID> {

    /**
     * Find all immunizations for a patient, ordered by administration date descending
     */
    List<PatientImmunization> findByPatient_IdOrderByAdministrationDateDesc(UUID patientId);

    /**
     * Find all immunizations for a patient at a specific hospital
     */
    List<PatientImmunization> findByPatient_IdAndHospital_IdOrderByAdministrationDateDesc(
            UUID patientId, UUID hospitalId);

    /**
     * Find immunizations by status
     */
    List<PatientImmunization> findByPatient_IdAndStatusOrderByAdministrationDateDesc(
            UUID patientId, String status);

    /**
     * Find all active immunizations for a patient
     */
    List<PatientImmunization> findByPatient_IdAndActiveTrue(UUID patientId);

    /**
     * Find overdue immunizations for a patient
     */
    @Query("SELECT i FROM PatientImmunization i WHERE i.patient.id = :patientId " +
            "AND i.overdue = true AND i.active = true ORDER BY i.nextDoseDueDate")
    List<PatientImmunization> findOverdueImmunizations(@Param("patientId") UUID patientId);

    /**
     * Find immunizations due within a date range
     */
    @Query("SELECT i FROM PatientImmunization i WHERE i.patient.id = :patientId " +
            "AND i.nextDoseDueDate BETWEEN :startDate AND :endDate " +
            "AND i.active = true ORDER BY i.nextDoseDueDate")
    List<PatientImmunization> findUpcomingDueDates(
            @Param("patientId") UUID patientId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Find all immunizations for a specific vaccine type
     */
    List<PatientImmunization> findByPatient_IdAndVaccineCodeOrderByAdministrationDateDesc(
            UUID patientId, String vaccineCode);

    /**
     * Find immunizations by vaccine type (broader category)
     */
    List<PatientImmunization> findByPatient_IdAndVaccineTypeOrderByAdministrationDateDesc(
            UUID patientId, String vaccineType);

    /**
     * Count immunizations for a patient
     */
    long countByPatient_Id(UUID patientId);

    /**
     * Find immunizations requiring school compliance
     */
    List<PatientImmunization> findByPatient_IdAndRequiredForSchoolTrueOrderByAdministrationDateDesc(
            UUID patientId);

    /**
     * Find immunizations requiring travel compliance
     */
    List<PatientImmunization> findByPatient_IdAndRequiredForTravelTrueOrderByAdministrationDateDesc(
            UUID patientId);

    /**
     * Find immunizations with adverse reactions
     */
    List<PatientImmunization> findByPatient_IdAndAdverseReactionTrueOrderByAdministrationDateDesc(
            UUID patientId);

    /**
     * Find immunizations administered by specific staff member
     */
    List<PatientImmunization> findByAdministeredBy_IdOrderByAdministrationDateDesc(UUID staffId);

    /**
     * Find immunizations that need reminders
     */
    @Query("SELECT i FROM PatientImmunization i WHERE i.patient.id = :patientId " +
            "AND i.nextDoseDueDate <= :reminderDate " +
            "AND (i.reminderSent = false OR i.reminderSent IS NULL) " +
            "AND i.active = true AND i.status = 'COMPLETED' " +
            "ORDER BY i.nextDoseDueDate")
    List<PatientImmunization> findImmunizationsNeedingReminders(
            @Param("patientId") UUID patientId,
            @Param("reminderDate") LocalDate reminderDate);

    /**
     * Check if patient has received a specific vaccine
     */
    boolean existsByPatient_IdAndVaccineCodeAndStatus(
            UUID patientId, String vaccineCode, String status);

    /**
     * Find incomplete vaccine series
     */
    @Query("SELECT i FROM PatientImmunization i WHERE i.patient.id = :patientId " +
            "AND i.doseNumber < i.totalDosesInSeries " +
            "AND i.active = true ORDER BY i.administrationDate DESC")
    List<PatientImmunization> findIncompleteSeries(@Param("patientId") UUID patientId);
}
