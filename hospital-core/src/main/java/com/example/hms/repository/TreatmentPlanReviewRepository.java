package com.example.hms.repository;

import com.example.hms.model.treatment.TreatmentPlanReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TreatmentPlanReviewRepository extends JpaRepository<TreatmentPlanReview, UUID> {

    List<TreatmentPlanReview> findByTreatmentPlanId(UUID treatmentPlanId);
}
