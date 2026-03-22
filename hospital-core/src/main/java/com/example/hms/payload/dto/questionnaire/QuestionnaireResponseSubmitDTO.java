package com.example.hms.payload.dto.questionnaire;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
public class QuestionnaireResponseSubmitDTO {
    @NotNull
    private UUID questionnaireId;

    private UUID appointmentId;

    @NotNull
    private Map<String, String> answers;
}
