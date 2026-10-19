package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The reason billing staff give to reverse or reject a payment (QA D140, D142). */
public record PaymentReasonRequestDTO(
    @NotBlank @Size(max = 500) String reason
) {
}
