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

    @NotBlank(message = "Recommendations are required to complete a consultation")
    @Size(max = 2000, message = "Recommendations must not exceed 2000 characters")
    private String recommendations;

    private String consultantNote;

    private Boolean followUpRequired;

    @Size(max = 1000, message = "Follow-up instructions must not exceed 1000 characters")
    private String followUpInstructions;
}
