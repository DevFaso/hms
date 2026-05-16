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

    /** Count CRITICAL (or any flag) results for orders placed by a given staff member. */
    long countByLabOrder_OrderingStaff_IdAndAbnormalFlag(UUID staffId, AbnormalFlag abnormalFlag);

    /**
     * Look up an existing result by its source HL7 message control id
     * (MSH-10). Used by {@code MllpInboundLabService} to short-circuit
     * analyzer retransmissions — if the id already exists we return
     * ACCEPTED without inserting a duplicate row. Paired with the
     * partial unique index from V98 so a concurrent retry that wins
     * the race still cannot insert two rows.
     */
    Optional<LabResult> findFirstBySourceMessageControlId(String sourceMessageControlId);

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
}

