package com.example.hms.repository;

import java.util.Collection;
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

    /**
     * SUPER_ADMIN cross-tenant fallback list. Overrides {@link JpaRepository#findAll(Pageable)}
     * so the same {@link EntityGraph} as the hospital-scoped queries is applied — without it
     * the mapper triggers lazy proxy initialisation per row, and any dangling FK
     * (e.g. a Patient hard-deleted while a Prescription still references it) raises
     * {@link jakarta.persistence.EntityNotFoundException} → 500 from
     * {@code GlobalExceptionHandler.handleEntityNotFound}.
     */
    @Override
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "encounter", "encounter.hospital", "hospital"})
    Page<Prescription> findAll(Pageable pageable);

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

    /** Pharmacist work queue: dispensable prescriptions at a hospital, ordered by creation date. */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "encounter", "encounter.hospital"})
    Page<Prescription> findByHospital_IdAndStatusIn(UUID hospitalId, List<PrescriptionStatus> statuses, Pageable pageable);

    /** Hospital-scoped tile count for the super-admin dashboard. */
    long countByHospital_Id(UUID hospitalId);

    /**
     * E8 #49 — the same read across every hospital the caller may read for this patient.
     *
     * <p>The graph must reach {@code staff.user}, not just {@code staff}: the
     * timeline renders the prescriber via {@code Staff.getFullName()}, which
     * dereferences the user, so stopping at {@code staff} would halve the round
     * trips instead of removing them.
     *
     * <p>It deliberately does NOT match the sibling finders above. They fetch
     * {@code encounter.hospital} because their callers render it; this caller
     * reads {@code prescription.getHospital()} directly, and needs
     * {@code encounter.department} instead — the sensitivity filter runs
     * {@code effectiveCategory(getEncounter())}, which falls back to the
     * department's default, so stopping at {@code encounter} leaves one lazy
     * load per row. {@code patient} is not fetched: this caller never touches
     * the association, and the row carries a dozen encrypted columns.
     */
    @EntityGraph(attributePaths = {"staff", "staff.user", "hospital", "encounter", "encounter.department"})
    List<Prescription> findByPatient_IdAndHospital_IdIn(UUID patientId, Collection<UUID> hospitalIds);
}
