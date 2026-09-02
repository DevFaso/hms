package com.example.hms.repository;

import com.example.hms.model.Appointment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID>, JpaSpecificationExecutor<Appointment> {

    // Custom queries for flexible appointment lookup
    @Query("SELECT a FROM Appointment a WHERE LOWER(a.patient.email) = LOWER(:email)")
    List<Appointment> findByPatientEmail(@Param("email") String email);

    @Query("SELECT a FROM Appointment a WHERE LOWER(a.patient.email) = LOWER(:email) AND a.hospital.id = :hospitalId")
    List<Appointment> findByPatientEmailAndHospitalId(@Param("email") String email, @Param("hospitalId") UUID hospitalId);

    @Query("SELECT a FROM Appointment a WHERE a.patient.phoneNumberPrimary = :phone")
    List<Appointment> findByPatientPhoneNumber(@Param("phone") String phone);

    @Query("SELECT a FROM Appointment a WHERE a.patient.phoneNumberPrimary = :phone AND a.hospital.id = :hospitalId")
    List<Appointment> findByPatientPhoneNumberAndHospitalId(@Param("phone") String phone, @Param("hospitalId") UUID hospitalId);

    @Query("SELECT a FROM Appointment a JOIN a.patient.hospitalRegistrations r WHERE r.mrn = :mrn")
    List<Appointment> findByPatientMrn(@Param("mrn") String mrn);

    @Query("SELECT a FROM Appointment a JOIN a.patient.hospitalRegistrations r WHERE r.mrn = :mrn AND a.hospital.id = :hospitalId")
    List<Appointment> findByPatientMrnAndHospitalId(@Param("mrn") String mrn, @Param("hospitalId") UUID hospitalId);

    @Query("SELECT a FROM Appointment a WHERE a.staff.licenseNumber = :number")
    List<Appointment> findByStaffNumber(@Param("number") String number);

    @Query("SELECT a FROM Appointment a WHERE a.staff.licenseNumber = :number AND a.hospital.id = :hospitalId")
    List<Appointment> findByStaffNumberAndHospitalId(@Param("number") String number, @Param("hospitalId") UUID hospitalId);

    @Query("SELECT a FROM Appointment a WHERE LOWER(a.staff.user.email) = LOWER(:email)")
    List<Appointment> findByStaffEmail(@Param("email") String email);

    @Query("SELECT a FROM Appointment a WHERE LOWER(a.staff.user.email) = LOWER(:email) AND a.hospital.id = :hospitalId")
    List<Appointment> findByStaffEmailAndHospitalId(@Param("email") String email, @Param("hospitalId") UUID hospitalId);

    @Query("SELECT a FROM Appointment a WHERE a.staff.id = :id")
    List<Appointment> findByStaffId(@Param("id") UUID id);

    @Query("SELECT a FROM Appointment a WHERE a.staff.id = :id AND a.hospital.id = :hospitalId")
    List<Appointment> findByStaffIdAndHospitalId(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId);

    List<Appointment> findByPatient_Id(UUID patientId);

    List<Appointment> findByStaff_Id(UUID staffId); // ✅ Fixed

    List<Appointment> findByHospital_Id(UUID hospitalId);

    List<Appointment> findByAppointmentDateBetween(LocalDate startDate, LocalDate endDate);

    long countByAppointmentDateBetween(LocalDate startDate, LocalDate endDate);

    List<Appointment> findByPatient_IdAndAppointmentDateAfter(UUID patientId, LocalDate date);

    List<Appointment> findByStaff_IdAndAppointmentDateAfter(UUID staffId, LocalDate date); // ✅ Fixed

    List<Appointment> findByStaff_IdAndAppointmentDate(UUID staffId, LocalDate date); // ✅ Fixed

    List<Appointment> findByHospital_IdAndStaff_Id(UUID hospitalId, UUID staffId); // ✅ Fixed

    List<Appointment> findByHospital_IdAndPatient_Id(UUID hospitalId, UUID patientId);

    /** FHIR Appointment search (Tier 2 item 43): newest first, capped by the caller. */
    org.springframework.data.domain.Page<Appointment>
        findByHospital_IdAndPatient_IdOrderByAppointmentDateDescStartTimeDesc(
            UUID hospitalId, UUID patientId, org.springframework.data.domain.Pageable pageable);

    List<Appointment> findByCreatedBy_Id(UUID userId);

    List<Appointment> findByHospital_IdAndCreatedBy_Id(UUID hospitalId, UUID userId);

    List<Appointment> findByHospital_IdOrderByAppointmentDateDesc(UUID hospitalId);
    List<Appointment> findAllByHospitalIdIn(Set<UUID> allowedHospitals);

    List<Appointment> findByHospital_IdAndAppointmentDate(UUID hospitalId, LocalDate appointmentDate);

    /**
     * Returns all appointments with DISTINCT applied at the JPQL level.
     * This guards against Hibernate "More than one row with the given identifier" errors
     * that can occur when a joined association (e.g. Staff) appears in multiple result rows.
     */
    @Query("SELECT DISTINCT a FROM Appointment a")
    List<Appointment> findAllDistinct();

    /**
     * Hospital-scoped date-range slice used by the Cadence calendar grid.
     * Backed by the existing index on hospital + appointment_date.
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"patient", "staff"})
    java.util.List<com.example.hms.model.Appointment> findByHospital_IdAndAppointmentDateBetween(
        java.util.UUID hospitalId, java.time.LocalDate startDate, java.time.LocalDate endDate);

    /** Same range query, additionally filtered by provider. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"patient", "staff"})
    java.util.List<com.example.hms.model.Appointment> findByHospital_IdAndStaff_IdAndAppointmentDateBetween(
        java.util.UUID hospitalId, java.util.UUID staffId, java.time.LocalDate startDate, java.time.LocalDate endDate);

    /**
     * Reminder-sweep feed (P1 #7): un-reminded appointments in the given
     * date range and statuses. Unscoped by design — the sweep is a system
     * actor covering every hospital. The exact "next N hours" window is
     * narrowed in Java because appointment date and start time are
     * separate columns. EntityGraph pulls what the reminder needs:
     * patient (+user for the in-app push), staff, hospital.
     *
     * <p>Tier 2 item 29 added the {@code deceasedAt IS NULL} clause. This
     * sweep selected purely on appointment state, so a patient who died last
     * week still had their follow-up reminder texted to the family. The
     * predicate is a column on the patient rather than a join to the death
     * record precisely so it stays cheap enough to belong here.
     */
    @org.springframework.data.jpa.repository.EntityGraph(
        attributePaths = {"patient", "patient.user", "staff", "staff.user", "hospital"})
    @org.springframework.data.jpa.repository.Query(
        "SELECT a FROM Appointment a WHERE a.reminderSentAt IS NULL "
        + "AND a.status IN :statuses "
        + "AND a.patient.deceasedAt IS NULL "
        + "AND a.appointmentDate BETWEEN :fromDate AND :toDate")
    java.util.List<com.example.hms.model.Appointment> findAwaitingReminder(
        @org.springframework.data.repository.query.Param("statuses")
        java.util.Collection<com.example.hms.enums.AppointmentStatus> statuses,
        @org.springframework.data.repository.query.Param("fromDate") java.time.LocalDate fromDate,
        @org.springframework.data.repository.query.Param("toDate") java.time.LocalDate toDate);
}
