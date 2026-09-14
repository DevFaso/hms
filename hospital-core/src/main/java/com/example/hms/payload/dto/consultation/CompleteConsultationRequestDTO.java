package com.example.hms.payload.dto.consultation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteConsultationRequestDTO {

    @NotBlank(message = "{consultation.recommendations.required}")
    @Size(max = 2000, message = "{consultation.recommendations.size}")
    private String recommendations;

    private String consultantNote;

    private Boolean followUpRequired;

    @Size(max = 1000, message = "{consultation.followUpInstructions.size}")
    private String followUpInstructions;
}
