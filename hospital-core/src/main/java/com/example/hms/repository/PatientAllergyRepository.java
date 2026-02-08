package com.example.hms.repository;

import com.example.hms.model.PatientAllergy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientAllergyRepository extends JpaRepository<PatientAllergy, UUID> {

    List<PatientAllergy> findByPatient_Id(UUID patientId);

    List<PatientAllergy> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);

    Optional<PatientAllergy> findByIdAndPatient_IdAndHospital_Id(UUID id, UUID patientId, UUID hospitalId);
}
