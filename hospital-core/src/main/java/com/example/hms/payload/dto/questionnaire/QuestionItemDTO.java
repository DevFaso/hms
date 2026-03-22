package com.example.hms.payload.dto.questionnaire;

import com.example.hms.enums.QuestionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class QuestionItemDTO {
    @NotBlank
    private String id;

    @NotBlank
    @Size(max = 500)
    private String question;

    @NotNull
    private QuestionType type;

    private boolean required;

    private List<String> options;
}
