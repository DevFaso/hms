package com.example.hms.repository;

import com.example.hms.model.LabQcEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LabQcEventRepository extends JpaRepository<LabQcEvent, UUID> {

    Page<LabQcEvent> findByHospitalId(UUID hospitalId, Pageable pageable);

    Page<LabQcEvent> findByTestDefinitionId(UUID testDefinitionId, Pageable pageable);
}
