package com.example.hms.repository;

import com.example.hms.enums.TreatmentPlanStatus;
import com.example.hms.model.treatment.TreatmentPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface TreatmentPlanRepository extends JpaRepository<TreatmentPlan, UUID>,
    JpaSpecificationExecutor<TreatmentPlan> {

    Page<TreatmentPlan> findAllByHospitalIdAndStatus(UUID hospitalId, TreatmentPlanStatus status, Pageable pageable);

    Page<TreatmentPlan> findAllByHospitalId(UUID hospitalId, Pageable pageable);

    Page<TreatmentPlan> findAllByPatientId(UUID patientId, Pageable pageable);

    Page<TreatmentPlan> findAllByStatus(TreatmentPlanStatus status, Pageable pageable);

    Optional<TreatmentPlan> findByIdAndHospitalId(UUID id, UUID hospitalId);
}
