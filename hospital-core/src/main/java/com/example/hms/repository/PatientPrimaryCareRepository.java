package com.example.hms.repository;

import com.example.hms.model.PatientPrimaryCare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientPrimaryCareRepository extends JpaRepository<PatientPrimaryCare, UUID> {

    Optional<PatientPrimaryCare> findByPatient_IdAndCurrentTrue(UUID patientId);

    List<PatientPrimaryCare> findByPatient_IdOrderByStartDateDesc(UUID patientId);

    @Query("SELECT p FROM PatientPrimaryCare p " +
        "WHERE p.hospital.id = :hospitalId AND p.current = true")
    List<PatientPrimaryCare> findCurrentByHospital(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT p FROM PatientPrimaryCare p " +
        "WHERE p.patient.id = :patientId AND p.hospital.id = :hospitalId AND p.current = true")
    Optional<PatientPrimaryCare> findCurrentByPatientAndHospital(@Param("patientId") UUID patientId,
                                                                 @Param("hospitalId") UUID hospitalId);
}
