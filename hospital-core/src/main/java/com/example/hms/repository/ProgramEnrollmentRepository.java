package com.example.hms.repository;

import com.example.hms.enums.CareProgram;
import com.example.hms.enums.ProgramEnrollmentStatus;
import com.example.hms.model.ProgramEnrollment;
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
     * The registry: every enrolment in one programme at one hospital, one
     * status at a time. Overdue-first, then by next expected visit, so the
     * top of the screen is the patient most in need of tracing.
     */
    @EntityGraph(attributePaths = {"patient", "enrolledBy", "enrolledBy.user"})
    @Query("""
        SELECT e FROM ProgramEnrollment e
        WHERE e.hospital.id = :hospitalId
          AND e.program = :program
          AND e.status = :status
        ORDER BY e.nextExpectedVisit ASC, e.enrolledOn ASC
    """)
    List<ProgramEnrollment> findRegistry(
        @Param("hospitalId") UUID hospitalId,
        @Param("program") CareProgram program,
        @Param("status") ProgramEnrollmentStatus status);

    /** One patient's enrolments at one hospital, every programme and state. */
    @EntityGraph(attributePaths = {"patient", "enrolledBy", "enrolledBy.user"})
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
