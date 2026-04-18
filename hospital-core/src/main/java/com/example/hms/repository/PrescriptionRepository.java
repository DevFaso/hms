package com.example.hms.repository;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, UUID> {

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "encounter", "encounter.hospital"})
    Page<Prescription> findByPatient_Id(UUID patientId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "encounter", "encounter.hospital"})
    Page<Prescription> findByStaff_Id(UUID staffId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "encounter", "encounter.hospital"})
    Page<Prescription> findByEncounter_Id(UUID encounterId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "encounter", "encounter.hospital"})
    List<Prescription> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);

    // Hospital-scoped queries for tenant isolation
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "encounter", "encounter.hospital"})
    Page<Prescription> findByHospital_Id(UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "encounter", "encounter.hospital"})
    Page<Prescription> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "encounter", "encounter.hospital"})
    Page<Prescription> findByStaff_IdAndHospital_Id(UUID staffId, UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "encounter", "encounter.hospital"})
    Page<Prescription> findByEncounter_IdAndHospital_Id(UUID encounterId, UUID hospitalId, Pageable pageable);

    /** Count prescriptions by prescribing staff and status (e.g. PENDING_CLARIFICATION). */
    long countByStaff_IdAndStatus(UUID staffId, PrescriptionStatus status);
}
