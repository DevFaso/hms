package com.example.hms.repository;

import com.example.hms.model.TransfusionAdministration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransfusionAdministrationRepository extends JpaRepository<TransfusionAdministration, UUID> {

    /** A unit is transfused once — backed by the unique index uq_admin_unit. */
    Optional<TransfusionAdministration> findByBloodUnit_Id(UUID bloodUnitId);

    List<TransfusionAdministration> findByRequest_IdOrderByStartedAtDesc(UUID requestId);

    List<TransfusionAdministration> findByPatient_IdAndHospital_IdOrderByStartedAtDesc(
        UUID patientId, UUID hospitalId);
}
