package com.example.hms.repository;

import com.example.hms.enums.CareProgram;
import com.example.hms.enums.ProgramEnrollmentStatus;
import com.example.hms.model.ProgramEnrollment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProgramEnrollmentRepository extends JpaRepository<ProgramEnrollment, UUID> {

    /**
     * The registry page: one programme at one hospital, one status at a
     * time. Overdue-first (next expected visit ascending), so page 0 row 0
     * is the patient most in need of tracing. Paged rather than a List —
     * a cohort is unbounded, and one request must not serialize every
     * enrolled patient's identity at once.
     *
     * <p>The graph reaches through the patient's hospital registrations
     * because the mapper renders the MRN; without it, N rows meant N
     * lazy registration queries.
     */
    @EntityGraph(attributePaths = {"patient", "patient.hospitalRegistrations",
        "patient.hospitalRegistrations.hospital", "enrolledBy", "enrolledBy.user"})
    @Query("""
        SELECT e FROM ProgramEnrollment e
        WHERE e.hospital.id = :hospitalId
          AND e.program = :program
          AND e.status = :status
        ORDER BY e.nextExpectedVisit ASC, e.enrolledOn ASC
    """)
    Page<ProgramEnrollment> findRegistry(
        @Param("hospitalId") UUID hospitalId,
        @Param("program") CareProgram program,
        @Param("status") ProgramEnrollmentStatus status,
        Pageable pageable);

    /** One patient's enrolments at one hospital, every programme and state. */
    @EntityGraph(attributePaths = {"patient", "patient.hospitalRegistrations",
        "patient.hospitalRegistrations.hospital", "enrolledBy", "enrolledBy.user"})
    @Query("""
        SELECT e FROM ProgramEnrollment e
        WHERE e.hospital.id = :hospitalId
          AND e.patient.id = :patientId
        ORDER BY e.enrolledOn DESC
    """)
    List<ProgramEnrollment> findByPatient(
        @Param("hospitalId") UUID hospitalId,
        @Param("patientId") UUID patientId);

    /** The partial unique index's application-level twin, for a readable 409. */
    Optional<ProgramEnrollment> findByPatientIdAndHospitalIdAndProgramAndStatus(
        UUID patientId, UUID hospitalId, CareProgram program, ProgramEnrollmentStatus status);

    /**
     * The care-gap sweep's candidate read (Tier 2 item 36): every ACTIVE
     * enrolment whose expected visit has passed AND whose missed date has
     * not already produced a recall. The NOT EXISTS lives in the query so
     * historical defaulters — whose recalls were closed without a visit —
     * drop out of the candidate set instead of being refetched and
     * exists-checked every night forever. Paged; the caller consumes the
     * frontier rather than iterating page numbers.
     *
     * <p>Deliberately NOT hospital-scoped, the licence-sweep precedent: the
     * sweep runs on a scheduler with no request context, never returns a
     * row to a user, and turns rows into recalls carrying their own hospital.
     */
    @EntityGraph(attributePaths = {"patient", "hospital"})
    @Query("""
        SELECT e FROM ProgramEnrollment e
        WHERE e.status = com.example.hms.enums.ProgramEnrollmentStatus.ACTIVE
          AND e.nextExpectedVisit < :cutoff
          AND NOT EXISTS (
              SELECT 1 FROM PatientRecall r
              WHERE r.programEnrollment.id = e.id
                AND r.dueDate = e.nextExpectedVisit
          )
        ORDER BY e.nextExpectedVisit ASC
    """)
    List<ProgramEnrollment> findUntracedOverdueActive(
        @Param("cutoff") java.time.LocalDate cutoff,
        org.springframework.data.domain.Pageable pageable);

    /** Registry header counts: how many enrolments per status in one programme. */
    @Query("""
        SELECT e.status, COUNT(e) FROM ProgramEnrollment e
        WHERE e.hospital.id = :hospitalId
          AND e.program = :program
        GROUP BY e.status
    """)
    List<Object[]> countByStatus(
        @Param("hospitalId") UUID hospitalId,
        @Param("program") CareProgram program);
}
