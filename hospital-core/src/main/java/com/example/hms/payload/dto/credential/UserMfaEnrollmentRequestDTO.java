package com.example.hms.payload.dto.credential;

import com.example.hms.enums.MfaMethodType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserMfaEnrollmentRequestDTO {

    @NotNull
    private MfaMethodType method;

    private String channel;

    private boolean enabled;

    private boolean primaryFactor;

    private LocalDateTime enrolledAt;

    /**
     * Ignored: only a verified code sets this (MfaService). Accepted so an
     * older client's payload still parses.
     */
    private LocalDateTime lastVerifiedAt;
}
