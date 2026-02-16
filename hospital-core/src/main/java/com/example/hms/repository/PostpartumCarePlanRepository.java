package com.example.hms.repository;

import com.example.hms.model.postpartum.PostpartumCarePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostpartumCarePlanRepository extends JpaRepository<PostpartumCarePlan, UUID> {

    Optional<PostpartumCarePlan> findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(
        UUID patientId,
        UUID hospitalId
    );

    Optional<PostpartumCarePlan> findByIdAndPatient_Id(UUID carePlanId, UUID patientId);

    Optional<PostpartumCarePlan> findByIdAndPatient_IdAndHospital_Id(UUID carePlanId, UUID patientId, UUID hospitalId);

    List<PostpartumCarePlan> findByPatient_IdAndActiveTrue(UUID patientId);
}
