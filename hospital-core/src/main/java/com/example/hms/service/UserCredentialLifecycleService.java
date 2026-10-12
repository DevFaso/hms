package com.example.hms.service;

import com.example.hms.payload.dto.credential.UserCredentialHealthDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentRequestDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactRequestDTO;

import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

public interface UserCredentialLifecycleService {

    List<UserCredentialHealthDTO> listCredentialHealth();

    UserCredentialHealthDTO getCredentialHealth(UUID userId);

    /**
     * A super-admin's change to any account's MFA records
     * ({@code PUT /super-admin/credentials/{userId}/mfa}), which can disable or
     * delete an authenticator, the actor's own included. The ACTOR's step-up
     * ({@code MfaService#requireStepUp}, {@code X-Mfa-Token}) comes first, so a
     * stolen super-admin session cannot switch MFA off without the second
     * factor. An actor with no active factor passes only in non-strict mode,
     * and the bypass is audited.
     */
    List<UserMfaEnrollmentDTO> upsertMfaEnrollmentsAsSuperAdmin(UUID actorId, UUID userId,
                                                                List<UserMfaEnrollmentRequestDTO> payload,
                                                                String stepUpCode);

    /**
     * The caller's own MFA records ({@code PUT /auth/credentials/mfa}), which
     * can disable or delete the authenticator. With an active one, the step-up
     * ({@code MfaService#requireStepUpToChangeAuthenticator}) guards the change
     * in the same transaction, on the row it locked.
     */
    List<UserMfaEnrollmentDTO> upsertOwnMfaEnrollments(UUID userId, List<UserMfaEnrollmentRequestDTO> payload,
                                                       Authentication caller, String stepUpCode);

    List<UserRecoveryContactDTO> upsertRecoveryContacts(UUID userId, List<UserRecoveryContactRequestDTO> payload);

    String sendRecoveryContactVerificationCode(UUID userId, UUID contactId);

    UserRecoveryContactDTO verifyRecoveryContact(UUID userId, UUID contactId, String code);

    void recordSuccessfulLogin(UUID userId);
}
