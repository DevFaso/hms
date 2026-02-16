package com.example.hms.repository;

import com.example.hms.model.Appointment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID>, JpaSpecificationExecutor<Appointment> {

    // Custom queries for flexible appointment lookup
    @Query("SELECT a FROM Appointment a WHERE LOWER(a.patient.email) = LOWER(:email)")
    List<Appointment> findByPatientEmail(@Param("email") String email);

    @Query("SELECT a FROM Appointment a WHERE a.patient.phoneNumberPrimary = :phone")
    List<Appointment> findByPatientPhoneNumber(@Param("phone") String phone);

    @Query("SELECT a FROM Appointment a JOIN a.patient.hospitalRegistrations r WHERE r.mrn = :mrn")
    List<Appointment> findByPatientMri(@Param("mrn") String mrn);

    @Query("SELECT a FROM Appointment a WHERE a.staff.licenseNumber = :number")
    List<Appointment> findByStaffNumber(@Param("number") String number);

    @Query("SELECT a FROM Appointment a WHERE LOWER(a.staff.user.email) = LOWER(:email)")
    List<Appointment> findByStaffEmail(@Param("email") String email);

    @Query("SELECT a FROM Appointment a WHERE a.staff.id = :id")
    List<Appointment> findByStaffId(@Param("id") UUID id);

    List<Appointment> findByPatient_Id(UUID patientId);

    List<Appointment> findByStaff_Id(UUID staffId); // ✅ Fixed

    List<Appointment> findByHospital_Id(UUID hospitalId);

    List<Appointment> findByAppointmentDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    List<Appointment> findByPatient_IdAndAppointmentDateAfter(UUID patientId, LocalDate date);

    List<Appointment> findByStaff_IdAndAppointmentDateAfter(UUID staffId, LocalDate date); // ✅ Fixed

    List<Appointment> findByStaff_IdAndAppointmentDate(UUID staffId, LocalDate date); // ✅ Fixed

    List<Appointment> findByHospital_IdAndStaff_Id(UUID hospitalId, UUID staffId); // ✅ Fixed

    List<Appointment> findByHospital_IdAndPatient_Id(UUID hospitalId, UUID patientId);

    List<Appointment> findByCreatedBy_Id(UUID userId);

    List<Appointment> findByHospital_IdAndCreatedBy_Id(UUID hospitalId, UUID userId);

    List<Appointment> findByHospital_IdOrderByAppointmentDateDesc(UUID hospitalId);
    List<Appointment> findAllByHospitalIdIn(Set<UUID> allowedHospitals);
}
