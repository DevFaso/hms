package com.example.hms.payload.dto.credential;

import com.example.hms.enums.MfaMethodType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMfaEnrollmentDTO {

    private MfaMethodType method;
    private String channel;
    private boolean enabled;
    private boolean primaryFactor;
    private LocalDateTime enrolledAt;
    private LocalDateTime lastVerifiedAt;
    /**
     * Verified at least once (MfaService#isConfirmed): replacing it needs the
     * step-up, and the portal offers "Replace" from this flag, never from its
     * own reading of the other fields.
     */
    private boolean confirmed;
}
