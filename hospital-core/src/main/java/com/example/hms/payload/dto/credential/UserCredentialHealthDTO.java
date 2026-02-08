package com.example.hms.payload.dto.credential;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCredentialHealthDTO {

    private UUID userId;
    private String username;
    private String email;
    private boolean active;
    private boolean forcePasswordChange;
    private LocalDateTime lastLoginAt;

    private long mfaEnrolledCount;
    private long verifiedMfaCount;
    private boolean hasPrimaryMfa;

    private long recoveryContactCount;
    private long verifiedRecoveryContacts;
    private boolean hasPrimaryRecoveryContact;

    private List<UserMfaEnrollmentDTO> mfaEnrollments;
    private List<UserRecoveryContactDTO> recoveryContacts;
}
