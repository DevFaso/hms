package com.example.hms.repository;

import com.example.hms.enums.QuestionnaireStatus;
import com.example.hms.model.questionnaire.QuestionnaireResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuestionnaireResponseRepository extends JpaRepository<QuestionnaireResponse, UUID> {
    List<QuestionnaireResponse> findByPatientId(UUID patientId);

    List<QuestionnaireResponse> findByPatientIdAndStatus(UUID patientId, QuestionnaireStatus status);

    boolean existsByPatientIdAndQuestionnaireId(UUID patientId, UUID questionnaireId);
}
