package com.example.hms.payload.dto.portal;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionnaireSubmissionDTO {

    @NotNull
    private UUID questionnaireId;

    /**
     * JSON object mapping question IDs to answers, e.g.
     * {"q1": true, "q2": 7, "q3": "No known allergies"}
     */
    @NotNull
    private String responses;
}
