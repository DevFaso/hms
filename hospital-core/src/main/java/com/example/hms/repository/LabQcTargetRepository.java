package com.example.hms.repository;

import com.example.hms.enums.LabQcEventLevel;
import com.example.hms.model.LabQcTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** r63 D2304: every read is scoped by the hospital the caller acts for. */
@Repository
public interface LabQcTargetRepository extends JpaRepository<LabQcTarget, UUID> {

    Optional<LabQcTarget> findByHospitalIdAndTestDefinition_IdAndQcLevel(UUID hospitalId, UUID testDefinitionId,
                                                                        LabQcEventLevel qcLevel);

    List<LabQcTarget> findByHospitalIdAndTestDefinition_Id(UUID hospitalId, UUID testDefinitionId);

    List<LabQcTarget> findByHospitalId(UUID hospitalId);
}
