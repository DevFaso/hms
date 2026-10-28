package com.example.hms.repository;

import com.example.hms.model.Encounter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import com.example.hms.enums.EncounterStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EncounterRepository
    extends JpaRepository<Encounter, UUID>, JpaSpecificationExecutor<Encounter> {

    @org.springframework.data.jpa.repository.Query("""
        SELECT e FROM Encounter e
        JOIN FETCH e.patient p
        JOIN FETCH p.user
        LEFT JOIN FETCH e.staff s
        LEFT JOIN FETCH s.user
        LEFT JOIN FETCH e.department d
        LEFT JOIN FETCH e.hospital h
        LEFT JOIN FETCH e.appointment a
        WHERE p.id = :patientId
    """)
    List<Encounter> findByPatient_Id(@org.springframework.data.repository.query.Param("patientId") UUID patientId);

    // Encounters by STAFF/DOCTOR (also fully fetched)
    @org.springframework.data.jpa.repository.Query("""
        SELECT e FROM Encounter e
        JOIN FETCH e.patient p
        JOIN FETCH p.user
        JOIN FETCH e.staff s
        JOIN FETCH s.user
        LEFT JOIN FETCH e.department d
        LEFT JOIN FETCH e.hospital h
        LEFT JOIN FETCH e.appointment a
        WHERE s.id = :staffId
    """)
    List<Encounter> findByStaff_Id(@org.springframework.data.repository.query.Param("staffId") UUID staffId);

    // Advanced filtering for performance
    Page<Encounter> findByHospital_IdAndStatus(UUID hospitalId, EncounterStatus status, Pageable pageable);

    Page<Encounter> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId, Pageable pageable);

    Page<Encounter> findByStaff_IdAndHospital_Id(UUID staffId, UUID hospitalId, Pageable pageable);

    Optional<Encounter> findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
        UUID patientId,
        UUID staffId,
        UUID hospitalId);

    // Date range filtering
    Page<Encounter> findByHospital_IdAndEncounterDateBetween(UUID hospitalId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    // Combined filters for flexible search
    Page<Encounter> findByHospital_IdAndPatient_IdAndStatusAndEncounterDateBetween(
        UUID hospitalId,
        UUID patientId,
        EncounterStatus status,
        LocalDateTime from,
        LocalDateTime to,
        Pageable pageable
    );

    boolean existsById(UUID id);

    /** Whether any encounter was recorded under this assignment (the attending's). */
    boolean existsByAssignment_Id(UUID assignmentId);

    // Duplicate guards
    boolean existsByHospital_IdAndPatient_IdAndEncounterDate(
        UUID hospitalId, UUID patientId, LocalDateTime encounterDate
    );

    boolean existsByHospital_IdAndPatient_IdAndAppointment_IdAndEncounterDate(
        UUID hospitalId, UUID patientId, UUID appointmentId, LocalDateTime encounterDate
    );

    Optional<Encounter> findByIdAndHospital_Id(@org.springframework.lang.NonNull UUID id, UUID hospitalId);

    Optional<Encounter> findByCode(String code);

    // Find active (in-progress) encounters for a specific doctor — used for "roomed patients"
    List<Encounter> findByStaff_IdAndStatus(UUID staffId, EncounterStatus status);

    /**
     * The patient's visits still open, with the clinician who holds each: the
     * portal's care team and message recipients (QA D115). The patient's own
     * chart across hospitals — ownership is the bound, not a hospital scope.
     */
    @Query("""
        SELECT e FROM Encounter e
        JOIN FETCH e.staff s
        JOIN FETCH s.user
        LEFT JOIN FETCH e.hospital
        WHERE e.patient.id = :patientId
          AND e.status NOT IN :closed
        ORDER BY e.encounterDate DESC
    """)
    List<Encounter> findOpenWithClinicianByPatientId(@Param("patientId") UUID patientId,
                                                     @Param("closed") Collection<EncounterStatus> closed);

    // Find the most recent encounter for a patient at a hospital in a given status
    Optional<Encounter> findFirstByPatient_IdAndHospital_IdAndStatusOrderByEncounterDateDesc(
        UUID patientId, UUID hospitalId, EncounterStatus status);

    @Query("SELECT e FROM Encounter e WHERE e.appointment.id IN :appointmentIds")
    List<Encounter> findByAppointmentIdIn(@Param("appointmentIds") List<UUID> appointmentIds);

    /**
     * The day's walk-ins: encounters with no appointment. QA D791: a
     * prescription's anchor has no appointment either, and is not a walk-in.
     */
    @Query("SELECT e FROM Encounter e WHERE e.hospital.id = :hospitalId AND e.appointment IS NULL AND e.encounterDate >= :from AND e.encounterDate < :to AND " + NOT_PRESCRIPTION_ANCHOR)
    List<Encounter> findWalkInsForHospitalAndPeriod(@Param("hospitalId") UUID hospitalId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Fetch all encounters for a hospital within a date range, eagerly loading
     * patient, staff (with user), department, and appointment to avoid N+1 (MVP 5 tracker board).
     * QA D791: a prescription's anchor is not on the board (it read as a completed visit).
     */
    @Query("""
        SELECT DISTINCT e FROM Encounter e
        JOIN FETCH e.patient p
        LEFT JOIN FETCH p.hospitalRegistrations
        LEFT JOIN FETCH e.staff s
        LEFT JOIN FETCH s.user
        LEFT JOIN FETCH e.department d
        LEFT JOIN FETCH e.appointment a
        WHERE e.hospital.id = :hospitalId
          AND e.encounterDate >= :from
          AND e.encounterDate < :to
          AND """ + NOT_PRESCRIPTION_ANCHOR)
    List<Encounter> findAllByHospitalAndDateRange(
            @Param("hospitalId") UUID hospitalId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Carry-over encounters: active (non-terminal) encounters from <em>before</em> a
     * cut-off date.  Used by the patient tracker board to surface encounters that
     * started on a prior day and are still in progress.
     */
    @Query("""
        SELECT DISTINCT e FROM Encounter e
        JOIN FETCH e.patient p
        LEFT JOIN FETCH p.hospitalRegistrations
        LEFT JOIN FETCH e.staff s
        LEFT JOIN FETCH s.user
        LEFT JOIN FETCH e.department d
        LEFT JOIN FETCH e.appointment a
        WHERE e.hospital.id = :hospitalId
          AND e.encounterDate < :before
          AND e.status NOT IN :terminalStatuses
    """)
    List<Encounter> findCarryOverEncounters(
            @Param("hospitalId") UUID hospitalId,
            @Param("before") LocalDateTime before,
            @Param("terminalStatuses") Collection<EncounterStatus> terminalStatuses);

    /**
     * Find all non-terminal encounters for a patient at a specific hospital.
     * Used when discharging an admission to auto-complete the linked encounter.
     */
    List<Encounter> findByPatient_IdAndHospital_IdAndStatusNotIn(
            UUID patientId, UUID hospitalId, Collection<EncounterStatus> statuses);

    /** An admission's own open encounters (QA D134): what its discharge closes. */
    List<Encounter> findByAdmissionIdAndStatusNotIn(UUID admissionId, Collection<EncounterStatus> statuses);

    /**
     * The patient's open hospital stay at this hospital led by this clinician
     * (QA D136): a prescription's encounter must carry its prescriber.
     */
    Optional<Encounter> findFirstByPatient_IdAndStaff_IdAndHospital_IdAndAdmissionIdIsNotNullAndStatusNotInOrderByEncounterDateDesc(
            UUID patientId, UUID staffId, UUID hospitalId, Collection<EncounterStatus> statuses);

    /**
     * The patient's open hospital stay at this hospital, whoever leads it
     * (QA D414): a consultant's order for an inpatient belongs to the stay.
     */
    Optional<Encounter> findFirstByPatient_IdAndHospital_IdAndAdmissionIdIsNotNullAndStatusNotInOrderByEncounterDateDesc(
            UUID patientId, UUID hospitalId, Collection<EncounterStatus> statuses);

    /** An admission's own encounter, most recent first (QA D135). */
    Optional<Encounter> findFirstByAdmissionIdOrderByEncounterDateDesc(UUID admissionId);

    /**
     * Unpaged list of encounters for a patient at a specific hospital, eagerly fetching
     * associations to avoid N+1 (used by record sharing).
     */
    @Query("""
        SELECT e FROM Encounter e
        JOIN FETCH e.patient p
        JOIN FETCH p.user
        LEFT JOIN FETCH e.staff s
        LEFT JOIN FETCH s.user
        LEFT JOIN FETCH e.department d
        LEFT JOIN FETCH e.hospital h
        LEFT JOIN FETCH e.appointment a
        WHERE p.id = :patientId
          AND e.hospital.id = :hospitalId
    """)
    List<Encounter> findAllByPatient_IdAndHospital_Id(
            @Param("patientId") UUID patientId,
            @Param("hospitalId") UUID hospitalId);

    /**
     * Paged variant for the chart-review aggregator. Eagerly fetches the few
     * associations the chart-review DTO needs (staff/user, department,
     * hospital) so we avoid N+1 lookups on the rendered rows. Sort + limit
     * are applied at the DB level via the {@link Pageable} argument.
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
        "patient",
        "staff",
        "staff.user",
        "department",
        "hospital"
    })
    Page<Encounter> findByPatient_IdOrderByEncounterDateDesc(UUID patientId, Pageable pageable);

    /** Hospital-scoped paged variant for the chart-review aggregator. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
        "patient",
        "staff",
        "staff.user",
        "department",
        "hospital"
    })
    Page<Encounter> findByPatient_IdAndHospital_IdOrderByEncounterDateDesc(
        UUID patientId, UUID hospitalId, Pageable pageable);

    /** E9 #59e — across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}). */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
        "patient",
        "staff",
        "staff.user",
        "department",
        "hospital"
    })
    List<Encounter> findByPatient_IdAndHospital_IdInOrderByEncounterDateDesc(UUID patientId, Collection<UUID> hospitalIds);

    /** E9 #60 — the paged sibling, for the chart review. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
        "patient",
        "staff",
        "staff.user",
        "department",
        "hospital"
    })
    Page<Encounter> findByPatient_IdAndHospital_IdInOrderByEncounterDateDesc(
        UUID patientId, Collection<UUID> hospitalIds, Pageable pageable);

    /**
     * Find COMPLETED encounters for a patient that do NOT have a corresponding
     * discharge summary. Used by the patient portal to backfill missing summaries.
     */
    @Query("""
        SELECT e FROM Encounter e
        JOIN FETCH e.patient p
        JOIN FETCH e.staff s
        LEFT JOIN FETCH e.hospital h
        LEFT JOIN FETCH e.assignment a
        WHERE p.id = :patientId
          AND e.status = com.example.hms.enums.EncounterStatus.COMPLETED
          AND NOT EXISTS (
              SELECT 1 FROM com.example.hms.model.discharge.DischargeSummary ds
              WHERE ds.encounter.id = e.id
          )
        ORDER BY e.checkoutTimestamp DESC
    """)
    List<Encounter> findCompletedWithoutDischargeSummary(@Param("patientId") UUID patientId);

    /**
     * Look up an encounter by the HL7 v2 reconciliation key
     * (MSH-3, MSH-4, PV1-19, hospital). Backed by the partial
     * unique index {@code uk_encounter_external_visit} added in V99.
     * Used by the ADT visit-sync projection to apply A08 updates to
     * the right Encounter row without scanning by patient.
     */
    Optional<Encounter> findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
        String externalSendingApplication,
        String externalSendingFacility,
        String externalVisitNumber,
        UUID hospitalId
    );

    /** Hospital-scoped tile count for the super-admin dashboard. */
    long countByHospital_Id(UUID hospitalId);

    /**
     * QA D759/D791: a prescription written outside any visit hangs on an
     * encounter of its own ({@link Encounter#PRESCRIPTION_ANCHOR_NOTE}). It is
     * bookkeeping, not a visit: everything that counts or lists visits leaves
     * it out with this clause (the guard is {@code PrescriptionAnchorExclusionGuardTest}).
     * A NULL note is a visit, so the clause is not a plain {@code <>}.
     */
    String NOT_PRESCRIPTION_ANCHOR =
        "(e.notes IS NULL OR e.notes <> '" + Encounter.PRESCRIPTION_ANCHOR_NOTE + "')";

    /** QA D791: the super-admin dashboard's visit count, platform-wide. */
    @Query("SELECT COUNT(e) FROM Encounter e WHERE " + NOT_PRESCRIPTION_ANCHOR)
    long countVisits();

    /** QA D791: the super-admin dashboard's visit count at one hospital. */
    @Query("SELECT COUNT(e) FROM Encounter e WHERE e.hospital.id = :hospitalId AND " + NOT_PRESCRIPTION_ANCHOR)
    long countVisitsByHospitalId(@Param("hospitalId") UUID hospitalId);

    /** QA D791: the encounter-activity report's rows, visits only. */
    @Query(value = "SELECT e FROM Encounter e WHERE e.hospital.id = :hospitalId"
            + " AND e.encounterDate BETWEEN :from AND :to AND " + NOT_PRESCRIPTION_ANCHOR,
        countQuery = "SELECT COUNT(e) FROM Encounter e WHERE e.hospital.id = :hospitalId"
            + " AND e.encounterDate BETWEEN :from AND :to AND " + NOT_PRESCRIPTION_ANCHOR)
    Page<Encounter> findVisitsByHospitalAndDateBetween(@Param("hospitalId") UUID hospitalId,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to,
                                                       Pageable pageable);

    /** QA D791: the chart review's Encounters tab ("Revue de dossier"), visits only, newest first. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
        "patient",
        "staff",
        "staff.user",
        "department",
        "hospital"
    })
    @Query(value = "SELECT e FROM Encounter e WHERE e.patient.id = :patientId AND " + NOT_PRESCRIPTION_ANCHOR
            + " ORDER BY e.encounterDate DESC",
        countQuery = "SELECT COUNT(e) FROM Encounter e WHERE e.patient.id = :patientId AND " + NOT_PRESCRIPTION_ANCHOR)
    Page<Encounter> findVisitsByPatientId(@Param("patientId") UUID patientId, Pageable pageable);

    /** QA D791: the same across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}). */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
        "patient",
        "staff",
        "staff.user",
        "department",
        "hospital"
    })
    @Query(value = "SELECT e FROM Encounter e WHERE e.patient.id = :patientId AND e.hospital.id IN :hospitalIds AND "
            + NOT_PRESCRIPTION_ANCHOR + " ORDER BY e.encounterDate DESC",
        countQuery = "SELECT COUNT(e) FROM Encounter e WHERE e.patient.id = :patientId AND e.hospital.id IN :hospitalIds AND "
            + NOT_PRESCRIPTION_ANCHOR)
    Page<Encounter> findVisitsByPatientIdAndHospitalIdIn(@Param("patientId") UUID patientId,
                                                         @Param("hospitalIds") Collection<UUID> hospitalIds,
                                                         Pageable pageable);

    /** E8 #48 — every encounter of this patient here; the resolver applies the status/decay rule. */
    List<Encounter> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);

    /** QA D936: serialises two check-ins of one encounter (row lock until the transaction ends). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT e.id FROM Encounter e WHERE e.id = :id")
    java.util.Optional<java.util.UUID> lockById(@org.springframework.data.repository.query.Param("id") java.util.UUID id);
}
