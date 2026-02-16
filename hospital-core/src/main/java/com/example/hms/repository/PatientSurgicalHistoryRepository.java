package com.example.hms.repository;

import com.example.hms.model.PatientSurgicalHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PatientSurgicalHistoryRepository extends JpaRepository<PatientSurgicalHistory, UUID> {

    List<PatientSurgicalHistory> findByPatient_Id(UUID patientId);

    List<PatientSurgicalHistory> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);
}
