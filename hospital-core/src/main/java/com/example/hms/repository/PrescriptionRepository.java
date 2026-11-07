package com.example.hms.repository;

import java.util.Collection;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, UUID> {

    /**
     * QA D1295 class: every order of one visit, in the order written. The
     * discharge reconciliation and the ward-order list read them all; they
     * read an unordered first 100 (or 200) before.
     */
    Pageable EVERY_ORDER_OF_THE_VISIT =
        Pageable.unpaged(org.springframework.data.domain.Sort.by("createdAt", "id"));

    /**
     * SUPER_ADMIN cross-tenant fallback list. Overrides {@link JpaRepository#findAll(Pageable)}
     * so the same {@link EntityGraph} as the hospital-scoped queries is applied — without it
     * the mapper triggers lazy proxy initialisation per row, and any dangling FK
     * (e.g. a Patient hard-deleted while a Prescription still references it) raises
     * {@link jakarta.persistence.EntityNotFoundException} → 500 from
     * {@code GlobalExceptionHandler.handleEntityNotFound}.
     */
    @Override
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital", "hospital"})
    Page<Prescription> findAll(Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByPatient_Id(UUID patientId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByStaff_Id(UUID staffId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByEncounter_Id(UUID encounterId, Pageable pageable);

    /**
     * QA D1314: the orders that can be on this hospital's MAR, for the whole
     * ward in one statement: active, and either the patient has a stay here or
     * the order was written on an open emergency visit (QA D514, D702).
     * {@code NurseTaskServiceImpl.onWardMar} still decides each row.
     */
    @Query("""
        SELECT p FROM Prescription p
        LEFT JOIN FETCH p.encounter e
        WHERE p.hospital.id = :hospitalId
          AND p.status IN :orderStatuses
          AND (p.patient.id IN (SELECT a.patient.id FROM Admission a
                                WHERE a.hospital.id = :hospitalId AND a.status IN ('ACTIVE', 'ON_LEAVE'))
               OR (e.encounterType = :emergency AND e.status NOT IN :closedVisit))
        """)
    List<Prescription> findWardMarOrders(@Param("hospitalId") UUID hospitalId,
                                         @Param("orderStatuses") Collection<PrescriptionStatus> orderStatuses,
                                         @Param("emergency") com.example.hms.enums.EncounterType emergency,
                                         @Param("closedVisit") Collection<com.example.hms.enums.EncounterStatus> closedVisit);

    /**
     * QA D1314: the search box of the prescription list. The page read every
     * row of the tab into the browser to filter it there: 26,706 statements,
     * 46 s and 3.2 GB allocated on 15,000 rows, for each search. What the box
     * matches: the drug, the patient's name, the prescriber's name (the staff
     * row's, which the list shows first, and the account's), accents ignored. {@code :namePattern} comes from {@code PatientMatchKeys.nameContainsPattern}.
     */
    String SEARCH_JOINS = " LEFT JOIN rx.patient p LEFT JOIN rx.staff s LEFT JOIN s.user u ";
    /**
     * What every comparison of the search box ends with. ESCAPE is spelled out:
     * Hibernate does not leave LIKE on PostgreSQL's default escape, and without
     * it the pattern's escaped {@code %} matched nothing ("Glucose 5%").
     */
    String SEARCH_LIKE = " LIKE :namePattern ESCAPE '\\'";
    /** The folded fields the search box reads, on the aliases of {@link #SEARCH_JOINS}. */
    String SEARCH_MATCH = "(" + PatientRepository.FOLD_OPEN + "rx.medicationName" + PatientRepository.FOLD_CLOSE + SEARCH_LIKE
        + " OR " + PatientRepository.FOLD_OPEN + "CONCAT(p.firstName, ' ', COALESCE(CONCAT(p.middleName, ' '), ''), p.lastName)"
        + PatientRepository.FOLD_CLOSE + SEARCH_LIKE
        + " OR " + PatientRepository.FOLD_OPEN + "CONCAT(p.firstName, ' ', p.lastName)" + PatientRepository.FOLD_CLOSE + SEARCH_LIKE
        + " OR " + PatientRepository.FOLD_OPEN + "CONCAT(p.lastName, ' ', p.firstName)" + PatientRepository.FOLD_CLOSE + SEARCH_LIKE
        + " OR " + PatientRepository.FOLD_OPEN + "s.name" + PatientRepository.FOLD_CLOSE + SEARCH_LIKE
        + " OR " + PatientRepository.FOLD_OPEN + "CONCAT(COALESCE(u.firstName, ''), ' ', COALESCE(u.lastName, ''))"
        + PatientRepository.FOLD_CLOSE + SEARCH_LIKE + ")";

    /** The search box at one hospital, every status. */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    /**
     * Order review: an existing order still to be used, under its lock, in id order. A dispense holding the lock
     * finishes first and is read as it left the row. A dispensed ward order may still have doses due; a dispensed
     * take-home order has none.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rx FROM Prescription rx WHERE rx.patient.id = :patientId "
        + "AND (rx.status IN :uncompleted OR (rx.status = com.example.hms.enums.PrescriptionStatus.DISPENSED "
        + "AND rx.takeHome = FALSE)) ORDER BY rx.id")
    List<Prescription> lockUncompletedForReview(@Param("patientId") UUID patientId,
                                                @Param("uncompleted") java.util.Collection<com.example.hms.enums.PrescriptionStatus> uncompleted);

    /** Order review: what can replace a held order: written since, not withdrawn. */
    @Query("SELECT rx FROM Prescription rx WHERE rx.patient.id = :patientId AND rx.hospital.id = :hospitalId "
        + "AND rx.createdAt >= :since AND rx.status NOT IN (com.example.hms.enums.PrescriptionStatus.CANCELLED, "
        + "com.example.hms.enums.PrescriptionStatus.DISCONTINUED) ORDER BY rx.createdAt DESC")
    List<Prescription> findWrittenSince(@Param("patientId") UUID patientId, @Param("hospitalId") UUID hospitalId,
                                        @Param("since") java.time.LocalDateTime since);

    @Query(value = "SELECT rx FROM Prescription rx" + SEARCH_JOINS
        + "WHERE rx.hospital.id = :hospitalId AND " + SEARCH_MATCH,
        countQuery = "SELECT COUNT(rx) FROM Prescription rx" + SEARCH_JOINS
        + "WHERE rx.hospital.id = :hospitalId AND " + SEARCH_MATCH)
    Page<Prescription> searchAtHospital(@Param("hospitalId") UUID hospitalId,
                                        @Param("namePattern") String namePattern, Pageable pageable);

    /** The search box at one hospital, one tab's statuses. */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    @Query(value = "SELECT rx FROM Prescription rx" + SEARCH_JOINS
        + "WHERE rx.hospital.id = :hospitalId AND rx.status IN :statuses AND " + SEARCH_MATCH,
        countQuery = "SELECT COUNT(rx) FROM Prescription rx" + SEARCH_JOINS
        + "WHERE rx.hospital.id = :hospitalId AND rx.status IN :statuses AND " + SEARCH_MATCH)
    Page<Prescription> searchAtHospitalInStatuses(@Param("hospitalId") UUID hospitalId,
                                                  @Param("statuses") Collection<PrescriptionStatus> statuses,
                                                  @Param("namePattern") String namePattern, Pageable pageable);

    /** The search box in a super-admin's global view, every status. */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital", "hospital"})
    @Query(value = "SELECT rx FROM Prescription rx" + SEARCH_JOINS + "WHERE " + SEARCH_MATCH,
        countQuery = "SELECT COUNT(rx) FROM Prescription rx" + SEARCH_JOINS + "WHERE " + SEARCH_MATCH)
    Page<Prescription> searchEveryHospital(@Param("namePattern") String namePattern, Pageable pageable);

    /** The search box in a super-admin's global view, one tab's statuses. */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital", "hospital"})
    @Query(value = "SELECT rx FROM Prescription rx" + SEARCH_JOINS
        + "WHERE rx.status IN :statuses AND " + SEARCH_MATCH,
        countQuery = "SELECT COUNT(rx) FROM Prescription rx" + SEARCH_JOINS
        + "WHERE rx.status IN :statuses AND " + SEARCH_MATCH)
    Page<Prescription> searchEveryHospitalInStatuses(@Param("statuses") Collection<PrescriptionStatus> statuses,
                                                     @Param("namePattern") String namePattern, Pageable pageable);

    /** QA D972 hand-off: a visit's prescription ids, withdrawn ones left out (checkout event). */
    @org.springframework.data.jpa.repository.Query(
        "select p.id from Prescription p where p.encounter.id = :encounterId and p.status not in :excluded")
    List<UUID> findIdsByEncounterIdAndStatusNotIn(
        @org.springframework.data.repository.query.Param("encounterId") UUID encounterId,
        @org.springframework.data.repository.query.Param("excluded") java.util.Collection<com.example.hms.enums.PrescriptionStatus> excluded);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    List<Prescription> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);

    // Hospital-scoped queries for tenant isolation
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByHospital_Id(UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId, Pageable pageable);

    /** E9 #59c — the paged prescription list across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}). */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital", "hospital"})
    Page<Prescription> findByPatient_IdAndHospital_IdIn(UUID patientId, Collection<UUID> hospitalIds, Pageable pageable);

    /**
     * QA D652 - the paged list across the readable hospitals, less the rows
     * another hospital never sent ({@code NeverSentRule}): a foreign row in
     * {@code unsent} is that hospital's work in progress, and its by-id read
     * answers 404 here. Rows of the acting hospital are kept whatever their status.
     */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital", "hospital"})
    @Query(value = "SELECT p FROM Prescription p WHERE p.patient.id = :patientId AND p.hospital.id IN :hospitalIds"
        + " AND (p.hospital.id = :actingHospitalId OR p.status NOT IN :unsent)",
        countQuery = "SELECT COUNT(p) FROM Prescription p WHERE p.patient.id = :patientId AND p.hospital.id IN :hospitalIds"
        + " AND (p.hospital.id = :actingHospitalId OR p.status NOT IN :unsent)")
    Page<Prescription> findSentAcrossHospitals(@Param("patientId") UUID patientId,
                                               @Param("hospitalIds") Collection<UUID> hospitalIds,
                                               @Param("actingHospitalId") UUID actingHospitalId,
                                               @Param("unsent") Collection<PrescriptionStatus> unsent,
                                               Pageable pageable);

    /** {@link #findSentAcrossHospitals} restricted to a set of statuses (Gap G12). */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital", "hospital"})
    @Query(value = "SELECT p FROM Prescription p WHERE p.patient.id = :patientId AND p.hospital.id IN :hospitalIds"
        + " AND (p.hospital.id = :actingHospitalId OR p.status NOT IN :unsent) AND p.status IN :statuses",
        countQuery = "SELECT COUNT(p) FROM Prescription p WHERE p.patient.id = :patientId AND p.hospital.id IN :hospitalIds"
        + " AND (p.hospital.id = :actingHospitalId OR p.status NOT IN :unsent) AND p.status IN :statuses")
    Page<Prescription> findSentAcrossHospitalsAndStatusIn(@Param("patientId") UUID patientId,
                                                          @Param("hospitalIds") Collection<UUID> hospitalIds,
                                                          @Param("actingHospitalId") UUID actingHospitalId,
                                                          @Param("unsent") Collection<PrescriptionStatus> unsent,
                                                          @Param("statuses") Collection<PrescriptionStatus> statuses,
                                                          Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByStaff_IdAndHospital_Id(UUID staffId, UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByEncounter_IdAndHospital_Id(UUID encounterId, UUID hospitalId, Pageable pageable);

    /* ── Gap G12: the same finders, restricted to a set of statuses ──────
     *
     * One derived finder per branch of PrescriptionServiceImpl.list rather
     * than a Specification: every read above carries an @EntityGraph, and a
     * Specification query drops it, which would put the mapper's four lazy
     * associations back on a per-row lazy load.
     */

    /**
     * The SUPER_ADMIN cross-tenant counterpart of {@link #findAll(Pageable)},
     * and it carries that override's {@code hospital} node for the same
     * reason: the mapper reads {@code prescription.getHospital()} on every
     * row, so leaving it out is 200 lazy loads on a full page.
     */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital", "hospital"})
    Page<Prescription> findByStatusIn(Collection<PrescriptionStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByPatient_IdAndStatusIn(
        UUID patientId, Collection<PrescriptionStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital", "hospital"})
    Page<Prescription> findByPatient_IdAndHospital_IdInAndStatusIn(
        UUID patientId, Collection<UUID> hospitalIds,
        Collection<PrescriptionStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByStaff_IdAndStatusIn(
        UUID staffId, Collection<PrescriptionStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByStaff_IdAndHospital_IdAndStatusIn(
        UUID staffId, UUID hospitalId, Collection<PrescriptionStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByEncounter_IdAndStatusIn(
        UUID encounterId, Collection<PrescriptionStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByEncounter_IdAndHospital_IdAndStatusIn(
        UUID encounterId, UUID hospitalId, Collection<PrescriptionStatus> statuses, Pageable pageable);

    /** Count prescriptions by prescribing staff and status (e.g. PENDING_CLARIFICATION). */
    long countByStaff_IdAndStatus(UUID staffId, PrescriptionStatus status);

    /** Pharmacist work queue: dispensable prescriptions at a hospital, ordered by creation date. */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    Page<Prescription> findByHospital_IdAndStatusIn(UUID hospitalId, List<PrescriptionStatus> statuses, Pageable pageable);

    /**
     * Pharmacist work queue, filter ALL: the rows of
     * {@link #findByHospital_IdAndStatusIn} less the ward orders of a stay
     * that has ended (QA D757): an order filed on a stay's own encounter once
     * that encounter is closed, unless it is marked take-home. Same rule as
     * {@code InpatientOrderEnd.endedWithItsStay}; the MINE and UNCLAIMED
     * queries carry it too.
     */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    @Query(value = "select p from Prescription p where p.hospital.id = :hospitalId and p.status in :statuses "
            + "and (p.takeHome = true or not exists (select se.id from Encounter se where se = p.encounter "
            + "and se.admissionId is not null and se.status in (com.example.hms.enums.EncounterStatus.COMPLETED, com.example.hms.enums.EncounterStatus.CANCELLED, com.example.hms.enums.EncounterStatus.LEFT_WITHOUT_BEING_SEEN))) ",
        countQuery = "select count(p) from Prescription p where p.hospital.id = :hospitalId and p.status in :statuses "
            + "and (p.takeHome = true or not exists (select se.id from Encounter se where se = p.encounter "
            + "and se.admissionId is not null and se.status in (com.example.hms.enums.EncounterStatus.COMPLETED, com.example.hms.enums.EncounterStatus.CANCELLED, com.example.hms.enums.EncounterStatus.LEFT_WITHOUT_BEING_SEEN))) ")
    Page<Prescription> findWorkQueue(@Param("hospitalId") UUID hospitalId,
                                     @Param("statuses") List<PrescriptionStatus> statuses,
                                     Pageable pageable);

    /**
     * G13 work queue, filter MINE: the rows of {@link #findByHospital_IdAndStatusIn}
     * with an active claim by {@code userId}. Active means stamped after
     * {@code activeAfter} (now minus the TTL, from the service clock), so a
     * test can drive time. Its own count query, with the same predicate.
     */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    @Query(value = "select p from Prescription p where p.hospital.id = :hospitalId and p.status in :statuses "
            + "and (p.takeHome = true or not exists (select se.id from Encounter se where se = p.encounter "
            + "and se.admissionId is not null and se.status in (com.example.hms.enums.EncounterStatus.COMPLETED, com.example.hms.enums.EncounterStatus.CANCELLED, com.example.hms.enums.EncounterStatus.LEFT_WITHOUT_BEING_SEEN))) "
            + "and exists (select c.id from PrescriptionQueueClaim c where c.prescription = p "
            + "and c.claimedBy.id = :userId and c.claimedAt > :activeAfter)",
        countQuery = "select count(p) from Prescription p where p.hospital.id = :hospitalId and p.status in :statuses "
            + "and (p.takeHome = true or not exists (select se.id from Encounter se where se = p.encounter "
            + "and se.admissionId is not null and se.status in (com.example.hms.enums.EncounterStatus.COMPLETED, com.example.hms.enums.EncounterStatus.CANCELLED, com.example.hms.enums.EncounterStatus.LEFT_WITHOUT_BEING_SEEN))) "
            + "and exists (select c.id from PrescriptionQueueClaim c where c.prescription = p "
            + "and c.claimedBy.id = :userId and c.claimedAt > :activeAfter)")
    Page<Prescription> findWorkQueueClaimedBy(@Param("hospitalId") UUID hospitalId,
                                              @Param("statuses") List<PrescriptionStatus> statuses,
                                              @Param("userId") UUID userId,
                                              @Param("activeAfter") LocalDateTime activeAfter,
                                              Pageable pageable);

    /**
     * G13 work queue, filter UNCLAIMED: the rows nobody holds an active claim
     * on AND no fill is prepared for (a prepared row cannot be claimed, so it
     * is not work someone could pick up). Its own count query, with the same
     * predicate.
     */
    @EntityGraph(attributePaths = {"patient", "staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "encounter", "encounter.hospital"})
    @Query(value = "select p from Prescription p where p.hospital.id = :hospitalId and p.status in :statuses "
            + "and (p.takeHome = true or not exists (select se.id from Encounter se where se = p.encounter "
            + "and se.admissionId is not null and se.status in (com.example.hms.enums.EncounterStatus.COMPLETED, com.example.hms.enums.EncounterStatus.CANCELLED, com.example.hms.enums.EncounterStatus.LEFT_WITHOUT_BEING_SEEN))) "
            + "and not exists (select c.id from PrescriptionQueueClaim c where c.prescription = p "
            + "and c.claimedAt > :activeAfter) "
            + "and not exists (select d.id from Dispense d where d.prescription = p "
            + "and d.status = com.example.hms.enums.DispenseStatus.PENDING)",
        countQuery = "select count(p) from Prescription p where p.hospital.id = :hospitalId and p.status in :statuses "
            + "and (p.takeHome = true or not exists (select se.id from Encounter se where se = p.encounter "
            + "and se.admissionId is not null and se.status in (com.example.hms.enums.EncounterStatus.COMPLETED, com.example.hms.enums.EncounterStatus.CANCELLED, com.example.hms.enums.EncounterStatus.LEFT_WITHOUT_BEING_SEEN))) "
            + "and not exists (select c.id from PrescriptionQueueClaim c where c.prescription = p "
            + "and c.claimedAt > :activeAfter) "
            + "and not exists (select d.id from Dispense d where d.prescription = p "
            + "and d.status = com.example.hms.enums.DispenseStatus.PENDING)")
    Page<Prescription> findWorkQueueUnclaimed(@Param("hospitalId") UUID hospitalId,
                                              @Param("statuses") List<PrescriptionStatus> statuses,
                                              @Param("activeAfter") LocalDateTime activeAfter,
                                              Pageable pageable);

    /**
     * The prescription with its row locked, for a caller about to make an
     * exclusive decision about it — SMS dispatch, which may hold at most one
     * open offer at a time. Two concurrent dispatches would otherwise both read
     * no open decision, supersede nothing, and leave two live offers that could
     * each be accepted.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Prescription p where p.id = :id")
    Optional<Prescription> findByIdForUpdate(@Param("id") UUID id);

    /**
     * {@link #findByIdForUpdate}, but only within one hospital: the
     * hospital is part of the locking query, so a caller can never take the
     * row lock on another tenant's prescription (#825 security finding 3).
     * Empty when the id is unknown OR belongs elsewhere; the caller answers
     * both with the same 404. For hospital-scoped writes; a super-admin in
     * global view (no hospital) uses {@link #findByIdForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Prescription p where p.id = :id and p.hospital.id = :hospitalId")
    Optional<Prescription> findByIdAndHospitalIdForUpdate(@Param("id") UUID id,
                                                          @Param("hospitalId") UUID hospitalId);

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
    @EntityGraph(attributePaths = {"staff", "staff.user", "staff.user.staffProfile", "staff.user.patientProfile", "hospital", "encounter", "encounter.department"})
    List<Prescription> findByPatient_IdAndHospital_IdIn(UUID patientId, Collection<UUID> hospitalIds);
}
