package com.example.hms.payload.dto.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaVerifyRequest(
        @NotBlank(message = "{mfa.enrollment.codeRequired}")
        @Size(min = 6, max = 8, message = "{mfa.enrollment.codeLength}") String code
) {}
