package com.example.hms.payload.dto.questionnaire;

import com.example.hms.enums.QuestionnaireStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class QuestionnaireResponseDTO {
    private UUID id;
    private UUID questionnaireId;
    private String questionnaireTitle;
    private UUID patientId;
    private UUID appointmentId;
    private QuestionnaireStatus status;
    private LocalDateTime submittedAt;
    private Map<String, String> answers;
}
