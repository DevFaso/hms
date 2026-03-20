package com.example.hms.repository;

import com.example.hms.model.questionnaire.PreVisitQuestionnaire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PreVisitQuestionnaireRepository extends JpaRepository<PreVisitQuestionnaire, UUID> {
    List<PreVisitQuestionnaire> findByHospitalIdAndActiveTrue(UUID hospitalId);

    List<PreVisitQuestionnaire> findByHospitalIdAndDepartmentIdAndActiveTrue(UUID hospitalId, UUID departmentId);
}
