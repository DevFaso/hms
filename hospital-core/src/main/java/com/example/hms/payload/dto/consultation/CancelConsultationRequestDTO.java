package com.example.hms.payload.dto.consultation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CancelConsultationRequestDTO {

    @NotBlank(message = "{consultation.cancellationReason.required}")
    @Size(max = 500, message = "{consultation.cancellationReason.size}")
    private String cancellationReason;
}
