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

    List<Appointment> findByCreatedBy_Id(UUID userId);

    List<Appointment> findByHospital_IdAndCreatedBy_Id(UUID hospitalId, UUID userId);

    List<Appointment> findByHospital_IdOrderByAppointmentDateDesc(UUID hospitalId);
    List<Appointment> findAllByHospitalIdIn(Set<UUID> allowedHospitals);

    /**
     * Returns all appointments with DISTINCT applied at the JPQL level.
     * This guards against Hibernate "More than one row with the given identifier" errors
     * that can occur when a joined association (e.g. Staff) appears in multiple result rows.
     */
    @Query("SELECT DISTINCT a FROM Appointment a")
    List<Appointment> findAllDistinct();
}
