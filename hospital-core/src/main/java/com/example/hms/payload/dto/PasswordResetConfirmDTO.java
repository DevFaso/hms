package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetConfirmDTO {
    // QA round 29 sweep: {} reached the service and answered 500 ("Token must not be null").
    @jakarta.validation.constraints.NotBlank
    private String token;
    @jakarta.validation.constraints.NotBlank
    private String newPassword;
}

