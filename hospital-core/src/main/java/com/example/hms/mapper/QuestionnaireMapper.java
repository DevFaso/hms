package com.example.hms.mapper;

import com.example.hms.model.questionnaire.PreVisitQuestionnaire;
import com.example.hms.model.questionnaire.QuestionnaireResponse;
import com.example.hms.payload.dto.questionnaire.PreVisitQuestionnaireDTO;
import com.example.hms.payload.dto.questionnaire.QuestionItemDTO;
import com.example.hms.payload.dto.questionnaire.QuestionnaireResponseDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionnaireMapper {

    private final ObjectMapper objectMapper;

    public PreVisitQuestionnaireDTO toPreVisitQuestionnaireDTO(PreVisitQuestionnaire questionnaire) {
        return PreVisitQuestionnaireDTO.builder()
                .id(questionnaire.getId())
                .title(questionnaire.getTitle())
                .description(questionnaire.getDescription())
                .questions(fromJson(questionnaire.getQuestionsJson()))
                .build();
    }

    public QuestionnaireResponseDTO toQuestionnaireResponseDTO(QuestionnaireResponse response) {
        return QuestionnaireResponseDTO.builder()
                .id(response.getId())
                .questionnaireId(response.getQuestionnaireId())
                .questionnaireTitle(response.getQuestionnaireTitle())
                .patientId(response.getPatientId())
                .appointmentId(response.getAppointmentId())
                .status(response.getStatus())
                .submittedAt(response.getSubmittedAt())
                .answers(answersFromJson(response.getAnswersJson()))
                .build();
    }

    private List<QuestionItemDTO> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (IOException e) {
            log.error("Failed to parse questions JSON: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private Map<String, String> answersFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (IOException e) {
            log.error("Failed to parse answers JSON: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }
}
