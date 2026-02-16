package com.example.hms.service;

import com.example.hms.payload.dto.credential.UserCredentialHealthDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentRequestDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactRequestDTO;

import java.util.List;
import java.util.UUID;

public interface UserCredentialLifecycleService {

    List<UserCredentialHealthDTO> listCredentialHealth();

    UserCredentialHealthDTO getCredentialHealth(UUID userId);

    List<UserMfaEnrollmentDTO> upsertMfaEnrollments(UUID userId, List<UserMfaEnrollmentRequestDTO> payload);

    List<UserRecoveryContactDTO> upsertRecoveryContacts(UUID userId, List<UserRecoveryContactRequestDTO> payload);

    void recordSuccessfulLogin(UUID userId);
}
