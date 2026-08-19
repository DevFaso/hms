package com.example.hms.payload.dto.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaVerifyRequest(
        @NotBlank @Size(min = 6, max = 8) String code
) {}
