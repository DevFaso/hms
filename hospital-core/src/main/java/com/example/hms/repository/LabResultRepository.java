package com.example.hms.repository;

import com.example.hms.model.LabResult;
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
}

