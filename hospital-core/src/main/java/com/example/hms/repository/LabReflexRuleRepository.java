package com.example.hms.repository;

import com.example.hms.model.LabReflexRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LabReflexRuleRepository extends JpaRepository<LabReflexRule, UUID> {

    List<LabReflexRule> findByTriggerTestDefinition_IdAndActiveTrue(UUID triggerTestDefinitionId);
}
