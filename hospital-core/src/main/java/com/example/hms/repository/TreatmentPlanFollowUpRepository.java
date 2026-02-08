package com.example.hms.repository;

import com.example.hms.model.treatment.TreatmentPlanFollowUp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TreatmentPlanFollowUpRepository extends JpaRepository<TreatmentPlanFollowUp, UUID> {

    List<TreatmentPlanFollowUp> findByTreatmentPlanId(UUID treatmentPlanId);

    Optional<TreatmentPlanFollowUp> findByIdAndTreatmentPlanId(UUID id, UUID treatmentPlanId);
}
