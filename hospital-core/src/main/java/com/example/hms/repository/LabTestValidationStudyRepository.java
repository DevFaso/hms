package com.example.hms.repository;

import com.example.hms.model.LabTestValidationStudy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LabTestValidationStudyRepository extends JpaRepository<LabTestValidationStudy, UUID> {

    List<LabTestValidationStudy> findByLabTestDefinition_IdOrderByStudyDateDesc(UUID definitionId);
}
