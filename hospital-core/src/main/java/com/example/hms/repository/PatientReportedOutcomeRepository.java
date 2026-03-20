package com.example.hms.repository;

import com.example.hms.enums.PatientReportedOutcomeType;
import com.example.hms.model.PatientReportedOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PatientReportedOutcomeRepository extends JpaRepository<PatientReportedOutcome, UUID> {

    List<PatientReportedOutcome> findByPatientIdOrderByReportDateDesc(UUID patientId);

    List<PatientReportedOutcome> findByPatientIdAndOutcomeTypeOrderByReportDateDesc(
            UUID patientId, PatientReportedOutcomeType outcomeType);
}
