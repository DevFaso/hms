package com.example.hms.repository;

import com.example.hms.model.highrisk.HighRiskPregnancyCarePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HighRiskPregnancyCarePlanRepository extends JpaRepository<HighRiskPregnancyCarePlan, UUID> {

    List<HighRiskPregnancyCarePlan> findByPatient_IdOrderByCreatedAtDesc(UUID patientId);

    Optional<HighRiskPregnancyCarePlan> findFirstByPatient_IdAndActiveTrueOrderByCreatedAtDesc(UUID patientId);

    long countByHospital_IdAndCreatedAtBetween(UUID hospitalId, LocalDate start, LocalDate end);
}
