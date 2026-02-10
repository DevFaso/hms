package com.example.hms.repository;

import com.example.hms.model.PatientProblem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PatientProblemRepository extends JpaRepository<PatientProblem, UUID> {

    List<PatientProblem> findByPatient_Id(UUID patientId);

    List<PatientProblem> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);
}
