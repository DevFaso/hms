package com.example.hms.repository;

import com.example.hms.enums.TransfusionAdministrationStatus;
import com.example.hms.model.TransfusionAdministration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransfusionAdministrationRepository extends JpaRepository<TransfusionAdministration, UUID> {

    /** A unit is transfused once — backed by the unique index uq_admin_unit. */
    Optional<TransfusionAdministration> findByBloodUnit_Id(UUID bloodUnitId);

    List<TransfusionAdministration> findByRequest_IdOrderByStartedAtDesc(UUID requestId);

    /** The request and the bag of an administration, read without loading any of them, so they can be locked first. */
    interface LockTarget {
        UUID getRequestId();

        UUID getUnitId();
    }

    @Query("SELECT a.request.id AS requestId, a.bloodUnit.id AS unitId FROM TransfusionAdministration a WHERE a.id = :id")
    Optional<LockTarget> findLockTarget(@Param("id") UUID id);

    /** Blood is running on this request right now. */
    boolean existsByRequest_IdAndStatus(UUID requestId, TransfusionAdministrationStatus status);

    List<TransfusionAdministration> findByPatient_IdAndHospital_IdOrderByStartedAtDesc(
        UUID patientId, UUID hospitalId);

    /** Order review: what is running for this patient, at any hospital. */
    List<TransfusionAdministration> findByPatient_IdAndStatus(UUID patientId, com.example.hms.enums.TransfusionAdministrationStatus status);
}
