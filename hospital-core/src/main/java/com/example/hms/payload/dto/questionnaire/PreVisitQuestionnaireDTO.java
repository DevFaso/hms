package com.example.hms.payload.dto.questionnaire;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class PreVisitQuestionnaireDTO {
    private UUID id;
    private String title;
    private String description;
    private List<QuestionItemDTO> questions;
    private UUID responseId; // if already submitted
    private LocalDateTime submittedAt; // if already submitted
}
