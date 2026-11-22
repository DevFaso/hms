package com.example.hms.repository;

import com.example.hms.enums.RefillStatus;
import com.example.hms.model.RefillRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefillRequestRepository extends JpaRepository<RefillRequest, UUID> {

    Page<RefillRequest> findByPatientId(UUID patientId, Pageable pageable);

    Page<RefillRequest> findByPatientIdAndStatus(UUID patientId, RefillStatus status, Pageable pageable);

    /** Guards against a second live request on the same prescription. */
    Optional<RefillRequest> findFirstByPrescription_IdAndPatient_IdAndStatusInOrderByCreatedAtDesc(
            UUID prescriptionId, UUID patientId, Collection<RefillStatus> statuses);

    Page<RefillRequest> findByPrescriptionId(UUID prescriptionId, Pageable pageable);

    /**
     * QA D3301 (r70): the requests {@code staffId} answers: those of their own prescriptions, and, as the
     * designated cover, those of a prescriber of the department they head who is no longer active (the staff
     * record or the account switched off). {@code RenewalAlertRouting} is the rule; the decision checks it again.
     */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT r FROM RefillRequest r JOIN r.prescription p JOIN p.staff s JOIN s.user u
            LEFT JOIN s.department d LEFT JOIN d.headOfDepartment h
        WHERE r.status IN :statuses
          AND (s.id = :staffId
               OR ((s.active = false OR u.isActive = false OR u.isDeleted = true) AND h.id = :staffId))
        """, countQuery = """
        SELECT COUNT(r) FROM RefillRequest r JOIN r.prescription p JOIN p.staff s JOIN s.user u
            LEFT JOIN s.department d LEFT JOIN d.headOfDepartment h
        WHERE r.status IN :statuses
          AND (s.id = :staffId
               OR ((s.active = false OR u.isActive = false OR u.isDeleted = true) AND h.id = :staffId))
        """)
    Page<RefillRequest> findAnsweredBy(@org.springframework.data.repository.query.Param("staffId") UUID staffId,
                                       @org.springframework.data.repository.query.Param("statuses") Collection<RefillStatus> statuses,
                                       Pageable pageable);

    /** QA D3301: how many requests {@code staffId} answers ({@link #findAnsweredBy}, counted). */
    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(r) FROM RefillRequest r JOIN r.prescription p JOIN p.staff s JOIN s.user u
            LEFT JOIN s.department d LEFT JOIN d.headOfDepartment h
        WHERE r.status IN :statuses
          AND (s.id = :staffId
               OR ((s.active = false OR u.isActive = false OR u.isDeleted = true) AND h.id = :staffId))
        """)
    long countAnsweredBy(@org.springframework.data.repository.query.Param("staffId") UUID staffId,
                         @org.springframework.data.repository.query.Param("statuses") Collection<RefillStatus> statuses);

    // Count pending refill requests for prescriptions written by a specific doctor (staff)
    long countByPrescription_Staff_IdAndStatus(UUID staffId, RefillStatus status);

    Page<RefillRequest> findByPrescription_Staff_Id(UUID staffId, Pageable pageable);

    Page<RefillRequest> findByPrescription_Staff_IdAndStatus(UUID staffId, RefillStatus status, Pageable pageable);

    // Unpaged variant used to build the prescriber's clinical-inbox rows.
    List<RefillRequest> findByPrescription_Staff_IdAndStatusOrderByCreatedAtDesc(UUID staffId, RefillStatus status);

    // ── Pharmacy-facing reads ────────────────────────────────────────
    // A pharmacist is never the prescriber, so the staff-scoped finders above
    // always return empty for them. Dispensing decisions need the whole
    // hospital's refill traffic instead.

    Page<RefillRequest> findByPrescription_Hospital_Id(UUID hospitalId, Pageable pageable);

    /** Unscoped status filter — only reachable by a super-admin (null active hospital). */
    Page<RefillRequest> findByStatus(RefillStatus status, Pageable pageable);

    long countByPrescription_Hospital_IdAndStatus(UUID hospitalId, RefillStatus status);

    long countByStatus(RefillStatus status);

    Page<RefillRequest> findByPrescription_Hospital_IdAndStatus(UUID hospitalId,
                                                               RefillStatus status,
                                                               Pageable pageable);

    /** Most recent decision on a prescription — drives the work-queue refill chip. */
    Optional<RefillRequest> findFirstByPrescription_IdOrderByUpdatedAtDesc(UUID prescriptionId);

    /** Most recent request in a given state, e.g. the APPROVED fill a dispense closes out. */
    Optional<RefillRequest> findFirstByPrescription_IdAndStatusOrderByUpdatedAtDesc(UUID prescriptionId,
                                                                                    RefillStatus status);

    /** Batch variant of {@link #findFirstByPrescription_IdOrderByUpdatedAtDesc} — avoids an
     *  N+1 when decorating a page of work-queue rows. */
    List<RefillRequest> findByPrescription_IdInOrderByUpdatedAtDesc(Collection<UUID> prescriptionIds);
}
