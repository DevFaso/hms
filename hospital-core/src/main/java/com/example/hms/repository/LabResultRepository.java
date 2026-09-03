package com.example.hms.repository;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.model.LabResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
    List<LabResult> findByLabOrder_Patient_Id(UUID patientId);

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
    List<LabResult> findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(
        UUID patientId,
        UUID labTestDefinitionId
    );

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

    /** Count CRITICAL (or any flag) results for orders placed by a given staff member. */
    long countByLabOrder_OrderingStaff_IdAndAbnormalFlag(UUID staffId, AbnormalFlag abnormalFlag);

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
     * Paged unscoped variant used by the chart-review aggregator when no
     * hospital scope is supplied. Sort + limit are applied at the DB level
     * via the {@link Pageable} argument so we avoid loading the entire
     * lab-result history into memory.
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
    Page<LabResult> findByLabOrder_Patient_Id(UUID patientId, Pageable pageable);

    /**
     * Hospital-scoped tile count for the super-admin dashboard. LabResult
     * has no direct hospital_id column — the scope flows through the
     * parent LabOrder.hospital. Derived via Spring Data's nested-property
     * naming.
     */
    long countByLabOrder_Hospital_Id(UUID hospitalId);
}

