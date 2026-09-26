package com.example.hms.repository;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.model.LabResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface LabResultRepository extends JpaRepository<LabResult, UUID> {

    /**
     * Escalation sweep feed (P0 #5): critical results whose provider was
     * notified and that are still unresolved past the cutoff. Unscoped by
     * design — the sweep is a system actor covering every hospital.
     *
     * <p>The last clause used to be {@code criticalEscalatedAt IS NULL}, which
     * made escalation one-shot: after a single nudge the row left the feed
     * forever, so a critical result nobody acknowledged went permanently quiet.
     * It now compares the LAST escalation against the same cutoff, so the sweep
     * re-fires on the configured interval until the result is resolved.
     *
     * <p>Resolved means acknowledged OR read back. Read-back is the stronger
     * act, but requiring it outright would strand results acknowledged before
     * V116 existed.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT r FROM LabResult r WHERE r.acknowledged = false "
        + "AND r.criticalReadBackAt IS NULL "
        + "AND r.criticalNotifiedAt IS NOT NULL AND r.criticalNotifiedAt < :cutoff "
        + "AND (r.criticalEscalatedAt IS NULL OR r.criticalEscalatedAt < :cutoff)")
    java.util.List<LabResult> findCriticalAwaitingEscalation(
        @org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);

    @Override
    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    Optional<LabResult> findById(UUID id);

    @Override
    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    List<LabResult> findAll();

    /**
     * Paginated unscoped {@code findAll} used by the super-admin
     * cross-tenant view ({@code LabResultServiceImpl.getLabResultsPage}
     * with {@code hospitalId == null}). Without this override Spring
     * Data resolves the inherited {@code findAll(Pageable)} from
     * {@link JpaRepository} which has no {@code @EntityGraph}, so the
     * mapper sees uninitialised proxies and the {@code Hibernate.isInitialized(...)}
     * defensive checks in {@code LabResultMapper#resolveHospitalName/...}
     * return null — surfacing as empty HOSPITAL / ORDER CODE / PATIENT NAME
     * / TEST columns on the cross-tenant Lab Results list page.
     *
     * <p>Identical attribute paths to the other {@code @EntityGraph}-decorated
     * finders in this repo so the mapper's expectations stay uniform
     * across scoped and unscoped queries.</p>
     */
    @Override
    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    Page<LabResult> findAll(Pageable pageable);

    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    List<LabResult> findByLabOrder_Id(UUID labOrderId);

    /** FHIR DiagnosticReport search (Tier 2 item 42): one query for a whole page of orders. */
    List<LabResult> findByLabOrder_IdIn(Collection<UUID> labOrderIds);

    /**
     * A patient's results that a hospital may read, sorted and limited by
     * {@code pageable} (pass {@link Pageable#unpaged()} for all of them).
     *
     * <p>Readable is B1's predicate widened by the patient-read rule, exactly
     * {@link #findTrendReadableAt}'s: an order placed at any of
     * {@code readableHospitalIds}, or an order the laboratory of
     * {@code actingHospitalId} performed for somebody else
     * ({@code LabOrder.isHandledBy}). {@code performingHospital} is null on
     * most orders, so the clause compares its id and never joins it: an inner
     * join would drop every order run where it was placed. A null
     * {@code actingHospitalId} switches the performed-here clause off, and the
     * query is then exactly "ordered at one of these hospitals".
     *
     * <p>Who calls it, and how wide each read is:
     * <ul>
     *   <li>the doctor timeline ({@code PatientServiceImpl.collectLabResultEntries})
     *       — the {@code RecordAccessPolicy} readable set (acting hospital plus
     *       the treatment relationship), {@code actingHospitalId} null: widened,
     *       ordered-at only;</li>
     *   <li>the doctor record's lab section
     *       ({@code PatientServiceImpl.collectDoctorRecordLabResults}) — the
     *       acting hospital alone, {@code actingHospitalId} null: NOT widened,
     *       unlike the medications and imaging beside it;</li>
     *   <li>{@link #findAllPatientResults} — every hospital, for the two
     *       callers that may read them all.</li>
     * </ul>
     * No caller passes an acting hospital today: neither surface above shows a
     * result the acting hospital only performed for another hospital, and the
     * query now leaves those rows unloaded instead of loading them to drop
     * them. The clause stays, pinned by {@code LabResultPatientReadableQueryTest},
     * so a surface that should show a laboratory its own work reads it with
     * #751's rule rather than a new one.
     *
     * <p>This replaced the unscoped {@code findByLabOrder_Patient_Id} pair,
     * which loaded every hospital's rows for the patient, fully hydrated, for
     * the callers to throw most of them away in memory. There is no
     * patient-only finder left to call by name.
     *
     * <p>{@code readableHospitalIds} must never be empty (PostgreSQL rejects
     * {@code IN ()}).
     *
     * <p>{@code labOrder.encounter} and its department are fetched because the
     * timeline's E8 #51 filter runs {@code effectiveCategory(labOrder.getEncounter())}
     * on every row before any of them is rendered, and that falls back to the
     * department's default when the encounter carries no explicit tag. Both are
     * LAZY, so without them the sensitivity check alone costs two selects per
     * lab order — on what is usually a chart's highest-count category.
     */
    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "labOrder.encounter",
        "labOrder.encounter.department",
        "assignment",
        "assignment.user"
    })
    @Query("""
        SELECT r FROM LabResult r
        WHERE r.labOrder.patient.id = :patientId
          AND (:globalView = true
               OR r.labOrder.hospital.id IN :readableHospitalIds
               OR r.labOrder.performingHospital.id = :actingHospitalId)
    """)
    List<LabResult> findPatientResultsReadableAt(@Param("patientId") UUID patientId,
                                                 @Param("readableHospitalIds") Collection<UUID> readableHospitalIds,
                                                 @Param("actingHospitalId") UUID actingHospitalId,
                                                 @Param("globalView") boolean globalView,
                                                 Pageable pageable);

    /**
     * Every hospital's results for the patient: the only call that sets
     * {@link #findPatientResultsReadableAt}'s {@code globalView} flag. Two
     * callers may read that wide, and only they call this:
     * <ul>
     *   <li>a verified super-admin in global view
     *       ({@code ChartReviewServiceImpl}; {@code PatientChartAccess} refuses a
     *       null scope to anyone else);</li>
     *   <li>the patient portal with no hospital
     *       ({@code PatientLabResultServiceImpl}), where the caller is the
     *       patient and owns every row.</li>
     * </ul>
     * The nil UUID names no hospital: the IN list must not be empty.
     */
    default List<LabResult> findAllPatientResults(UUID patientId, Pageable pageable) {
        return findPatientResultsReadableAt(patientId, Set.of(new UUID(0L, 0L)), null, true, pageable);
    }

    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    List<LabResult> findByLabOrder_Hospital_IdIn(Collection<UUID> hospitalIds);

    /**
     * B1: results of orders the hospitals order OR perform (V161
     * performing_hospital_id).
     *
     * <p>Carries the same entity graph as the {@code _IdIn} finder it stands
     * in for. Without it every row costs the mapper about six extra selects,
     * and worse: {@code LabResultMapper}'s {@code Hibernate.isInitialized}
     * guards return null for an uninitialised proxy, so the HOSPITAL / ORDER
     * CODE / PATIENT NAME / TEST columns come back empty rather than slow.
     */
    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    @Query("""
        SELECT r FROM LabResult r
        WHERE r.labOrder.hospital.id IN :hospitalIds
           OR r.labOrder.performingHospital.id IN :hospitalIds
    """)
    List<LabResult> findHandledByHospitals(@Param("hospitalIds") Collection<UUID> hospitalIds);

    /** B1: paged results of orders the hospital orders OR performs. Same graph, same reason. */
    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    @Query(value = """
        SELECT r FROM LabResult r
        WHERE r.labOrder.hospital.id = :hospitalId
           OR r.labOrder.performingHospital.id = :hospitalId
    """, countQuery = """
        SELECT COUNT(r) FROM LabResult r
        WHERE r.labOrder.hospital.id = :hospitalId
           OR r.labOrder.performingHospital.id = :hospitalId
    """)
    Page<LabResult> findHandledByHospital(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    Page<LabResult> findByLabOrder_Hospital_IdIn(Collection<UUID> hospitalIds, Pageable pageable);

    /**
     * A patient's newest results for one test that a hospital may read: the
     * orders placed at any of {@code readableHospitalIds} (the acting hospital
     * plus the treatment-relationship set), and the orders this hospital's
     * laboratory performed for somebody else (B1).
     *
     * <p>This is the ONLY patient-trend query on the repository. There used to
     * be an unscoped {@code findTop12ByLabOrder_Patient_Id...} beside it, and
     * three endpoints leaked every hospital's values through it; a trend query
     * that answers without a hospital does not exist to be found by name.
     * {@code globalView} is the single way to read across hospitals, and only
     * {@code LabResultServiceImpl} sets it, for a verified super-admin with no
     * hospital pinned. {@code readableHospitalIds} must never be empty
     * (PostgreSQL rejects {@code IN ()}).
     */
    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    @Query("""
        SELECT r FROM LabResult r
        WHERE r.labOrder.patient.id = :patientId
          AND r.labOrder.labTestDefinition.id = :labTestDefinitionId
          AND (:globalView = true
               OR r.labOrder.hospital.id IN :readableHospitalIds
               OR r.labOrder.performingHospital.id = :actingHospitalId)
        ORDER BY r.resultDate DESC
    """)
    List<LabResult> findTrendReadableAt(@Param("patientId") UUID patientId,
                                        @Param("labTestDefinitionId") UUID labTestDefinitionId,
                                        @Param("readableHospitalIds") Collection<UUID> readableHospitalIds,
                                        @Param("actingHospitalId") UUID actingHospitalId,
                                        @Param("globalView") boolean globalView,
                                        Pageable pageable);

    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    List<LabResult> findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
        UUID patientId,
        UUID hospitalId,
        Pageable pageable
    );

    /** E9 #59b — results across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}). */
    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    List<LabResult> findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(
        UUID patientId,
        Collection<UUID> hospitalIds,
        Pageable pageable
    );

    /**
     * Page-returning variant used by FHIR {@code Patient/$everything}
     * so {@code Page.hasNext()} can drive the {@code Bundle.link[next]}
     * continuation. The list-returning sibling above stays for
     * callers that don't need overflow detection.
     */
    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    Page<LabResult> findPageByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
        UUID patientId,
        UUID hospitalId,
        Pageable pageable
    );

    /** E9 #60b — the paged readable-set sibling, for FHIR {@code $everything}. */
    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.hospital",
        "labOrder.labTestDefinition",
        "labOrder.orderingStaff",
        "labOrder.orderingStaff.user",
        "assignment",
        "assignment.user"
    })
    Page<LabResult> findPageByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(
        UUID patientId,
        Collection<UUID> hospitalIds,
        Pageable pageable
    );

    /**
     * The REST ingest adapter's replay lookup: the same message triple, but
     * ONLY within the order it was posted against.
     *
     * <p>The unscoped sibling below is safe where it is used — the MLLP path
     * has already resolved the sending analyzer to a hospital before it asks
     * — but the REST adapter takes all three values from the request body. A
     * caller who wrote an MSH copying another hospital's analyzer, facility
     * and control id would match that hospital's row and be handed it back
     * in full, patient name and result value included. Scoped to the order
     * id, a replay can only ever match a message already recorded against
     * the very order the caller named, which is the order their own tenancy
     * check already covered.
     */
    Optional<LabResult> findFirstByLabOrder_IdAndSourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
        UUID labOrderId,
        String sourceSendingApplication,
        String sourceSendingFacility,
        String sourceMessageControlId);

    /**
     * Look up an existing result by the composite
     * (MSH-3 sending application, MSH-4 sending facility, MSH-10 control id)
     * idempotency key. Used by {@code MllpInboundLabService} to
     * short-circuit analyzer retransmissions: a retransmit from the
     * same analyzer reuses all three values, so we hit and return
     * ACCEPTED without inserting a duplicate row. The composite scope
     * is critical because HL7 v2 only guarantees MSH-10 uniqueness
     * within a sending system — two different analyzers can legitimately
     * emit the same control id and those must stay as separate rows.
     * Paired with the partial unique index from V98.
     */
    Optional<LabResult> findFirstBySourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
        String sourceSendingApplication,
        String sourceSendingFacility,
        String sourceMessageControlId);

    /**
     * The doctor's critical strip (B15): critical results of this provider's
     * orders that nobody has acknowledged yet, no older than the floor. The
     * strip used to count every CRITICAL result ever filed for the staff and
     * show it as the live safety-alert count.
     */
    long countByLabOrder_OrderingStaff_IdAndAbnormalFlagAndAcknowledgedFalseAndCreatedAtAfter(
        UUID staffId, AbnormalFlag abnormalFlag, java.time.LocalDateTime floor);

    /**
     * Hospital-scoped tile count for the super-admin dashboard. LabResult
     * has no direct hospital_id column — the scope flows through the
     * parent LabOrder.hospital. Derived via Spring Data's nested-property
     * naming.
     */
    long countByLabOrder_Hospital_Id(UUID hospitalId);

    /**
     * B14 — the release worklist: every row of the hospital nobody has
     * released yet, hand-entered or analyzer-ingested alike. With
     * {@code hms.lab.auto-verification.enabled=false} (the default) an ORU
     * observation lands here and stays "pending" for the patient until a
     * lab user releases it, so a worklist MUST be able to find it.
     */
    @EntityGraph(attributePaths = {
        "labOrder",
        "labOrder.patient",
        "labOrder.labTestDefinition"
    })
    @Query(value = """
        SELECT r FROM LabResult r
        WHERE r.released = false
          AND COALESCE(r.labOrder.performingHospital.id, r.labOrder.hospital.id) = :hospitalId
    """, countQuery = """
        SELECT COUNT(r) FROM LabResult r
        WHERE r.released = false
          AND COALESCE(r.labOrder.performingHospital.id, r.labOrder.hospital.id) = :hospitalId
    """)
    Page<LabResult> findPendingReleaseHandledBy(@Param("hospitalId") UUID hospitalId, Pageable pageable);
}

