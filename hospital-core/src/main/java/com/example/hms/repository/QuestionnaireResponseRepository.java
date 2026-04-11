package com.example.hms.repository;

import com.example.hms.model.QuestionnaireResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuestionnaireResponseRepository extends JpaRepository<QuestionnaireResponse, UUID> {

    List<QuestionnaireResponse> findByAppointment_Id(UUID appointmentId);

    Optional<QuestionnaireResponse> findByQuestionnaire_IdAndAppointment_Id(UUID questionnaireId, UUID appointmentId);

    boolean existsByQuestionnaire_IdAndAppointment_Id(UUID questionnaireId, UUID appointmentId);
}
