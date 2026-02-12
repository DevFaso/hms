package com.example.hms.service;

import com.example.hms.enums.MfaMethodType;
import com.example.hms.enums.RecoveryContactType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.User;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.model.UserRecoveryContact;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentRequestDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactRequestDTO;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.repository.UserRecoveryContactRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCredentialLifecycleServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMfaEnrollmentRepository mfaEnrollmentRepository;

    @Mock
    private UserRecoveryContactRepository recoveryContactRepository;

    @InjectMocks
    private UserCredentialLifecycleServiceImpl service;

    @Test
    void recordSuccessfulLoginUpdatesLastLogin() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
            .username("login-user")
            .email("login@example.com")
            .passwordHash("hash")
            .build();
        user.setId(userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        service.recordSuccessfulLogin(userId);

        assertThat(user.getLastLoginAt()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    void recordSuccessfulLoginWithNullUserIdDoesNothing() {
        service.recordSuccessfulLogin(null);

        verifyNoInteractions(userRepository);
    }

    @Test
    void recordSuccessfulLoginWhenUserMissingDoesNotAttemptSave() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        service.recordSuccessfulLogin(userId);

        verify(userRepository).findById(userId);
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void listCredentialHealthAggregatesCounts() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
            .username("alice")
            .email("alice@example.com")
            .build();
        user.setId(userId);
        user.setActive(true);

        UserMfaEnrollment enrollment = UserMfaEnrollment.builder()
            .user(user)
            .method(MfaMethodType.TOTP)
            .enabled(true)
            .primaryFactor(true)
            .lastVerifiedAt(LocalDateTime.now().minusDays(1))
            .build();
        enrollment.setId(UUID.randomUUID());

        UserRecoveryContact contact = UserRecoveryContact.builder()
            .user(user)
            .contactType(RecoveryContactType.EMAIL)
            .contactValue("alice.recovery@example.com")
            .verified(true)
            .primaryContact(true)
            .build();
        contact.setId(UUID.randomUUID());

        when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));
        when(mfaEnrollmentRepository.findAll()).thenReturn(List.of(enrollment));
        when(recoveryContactRepository.findAll()).thenReturn(List.of(contact));

        var results = service.listCredentialHealth();

        assertThat(results).hasSize(1);
        var dto = results.get(0);
        assertThat(dto.getUserId()).isEqualTo(userId);
        assertThat(dto.getMfaEnrolledCount()).isEqualTo(1);
        assertThat(dto.getVerifiedMfaCount()).isEqualTo(1);
        assertThat(dto.getRecoveryContactCount()).isEqualTo(1);
        assertThat(dto.getVerifiedRecoveryContacts()).isEqualTo(1);
        assertThat(dto.isHasPrimaryMfa()).isTrue();
        assertThat(dto.isHasPrimaryRecoveryContact()).isTrue();
    }

    @Test
    void getCredentialHealthAggregatesForUser() {
        UUID userId = UUID.randomUUID();
        LocalDateTime lastLogin = LocalDateTime.now().minusHours(4);

        User user = User.builder()
            .username("health-user")
            .email("health@example.com")
            .build();
        user.setId(userId);
        user.setActive(true);
        user.setForcePasswordChange(true);
        user.setLastLoginAt(lastLogin);

        UserMfaEnrollment primary = UserMfaEnrollment.builder()
            .user(user)
            .method(MfaMethodType.TOTP)
            .enabled(true)
            .primaryFactor(true)
            .lastVerifiedAt(LocalDateTime.now().minusDays(1))
            .build();
        primary.setId(UUID.randomUUID());

        UserMfaEnrollment secondary = UserMfaEnrollment.builder()
            .user(user)
            .method(MfaMethodType.SMS)
            .channel("+15550001234")
            .enabled(false)
            .primaryFactor(false)
            .build();
        secondary.setId(UUID.randomUUID());

        UserRecoveryContact recovery = UserRecoveryContact.builder()
            .user(user)
            .contactType(RecoveryContactType.EMAIL)
            .contactValue("health.recovery@example.com")
            .verified(true)
            .primaryContact(true)
            .verifiedAt(LocalDateTime.now().minusDays(2))
            .build();
        recovery.setId(UUID.randomUUID());

        when(userRepository.findByIdWithRolesAndProfiles(userId)).thenReturn(Optional.of(user));
        when(mfaEnrollmentRepository.findByUserId(userId)).thenReturn(List.of(primary, secondary));
        when(recoveryContactRepository.findByUserId(userId)).thenReturn(List.of(recovery));

        var dto = service.getCredentialHealth(userId);

        assertThat(dto.getUserId()).isEqualTo(userId);
        assertThat(dto.getUsername()).isEqualTo("health-user");
        assertThat(dto.getEmail()).isEqualTo("health@example.com");
        assertThat(dto.isActive()).isTrue();
        assertThat(dto.isForcePasswordChange()).isTrue();
        assertThat(dto.getLastLoginAt()).isEqualTo(lastLogin);
        assertThat(dto.getMfaEnrollments()).hasSize(2);
        assertThat(dto.getMfaEnrolledCount()).isEqualTo(1);
        assertThat(dto.getVerifiedMfaCount()).isEqualTo(1);
        assertThat(dto.isHasPrimaryMfa()).isTrue();
        assertThat(dto.getRecoveryContacts()).hasSize(1);
        assertThat(dto.getRecoveryContactCount()).isEqualTo(1);
        assertThat(dto.getVerifiedRecoveryContacts()).isEqualTo(1);
        assertThat(dto.isHasPrimaryRecoveryContact()).isTrue();
    }

    @Test
    void getCredentialHealthWhenUserMissingThrowsException() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByIdWithRolesAndProfiles(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCredentialHealth(userId))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(userRepository).findByIdWithRolesAndProfiles(userId);
        verifyNoInteractions(mfaEnrollmentRepository, recoveryContactRepository);
    }

    @Test
    void upsertMfaEnrollmentsReplacesPreviousEntries() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("mfa-user").email("mfa@example.com").build();
        user.setId(userId);

        UserMfaEnrollment existing = UserMfaEnrollment.builder()
            .user(user)
            .method(MfaMethodType.SMS)
            .channel("+1234567890")
            .enabled(true)
            .primaryFactor(false)
            .build();
        existing.setId(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mfaEnrollmentRepository.findByUserId(userId)).thenReturn(List.of(existing));
        when(mfaEnrollmentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        UserMfaEnrollmentRequestDTO request = new UserMfaEnrollmentRequestDTO();
        request.setMethod(MfaMethodType.TOTP);
        request.setEnabled(true);
        request.setPrimaryFactor(true);

        var response = service.upsertMfaEnrollments(userId, List.of(request));

        assertThat(response).hasSize(1);
        assertThat(response.get(0).getMethod()).isEqualTo(MfaMethodType.TOTP);
        verify(mfaEnrollmentRepository).delete(existing);
    }

    @Test
    void upsertMfaEnrollmentsEnforcesSinglePrimaryFactor() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("primary-user").email("primary@example.com").build();
        user.setId(userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mfaEnrollmentRepository.findByUserId(userId)).thenReturn(List.of());

        List<UserMfaEnrollment> persisted = new ArrayList<>();
        when(mfaEnrollmentRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<UserMfaEnrollment> argument = invocation.getArgument(0);
            persisted.clear();
            persisted.addAll(argument);
            return argument;
        });

    UserMfaEnrollmentRequestDTO sms = new UserMfaEnrollmentRequestDTO();
    sms.setMethod(MfaMethodType.SMS);
    sms.setChannel("  +14443332222  ");
    sms.setEnabled(true);
    sms.setPrimaryFactor(true);

    UserMfaEnrollmentRequestDTO totp = new UserMfaEnrollmentRequestDTO();
    totp.setMethod(MfaMethodType.TOTP);
    totp.setEnabled(true);
    totp.setPrimaryFactor(true);

    List<UserMfaEnrollmentRequestDTO> payload = new ArrayList<>();
    payload.add(null);
    payload.add(sms);
    payload.add(new UserMfaEnrollmentRequestDTO());
    payload.add(totp);

    var response = service.upsertMfaEnrollments(userId, payload);

    assertThat(response).hasSize(2);
    assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).isPrimaryFactor()).isFalse();
    assertThat(persisted.get(1).isPrimaryFactor()).isTrue();
    assertThat(persisted.get(0).getChannel()).isEqualTo("+14443332222");
    }

    @Test
    void upsertMfaEnrollmentsWithNullPayloadDeletesExisting() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("cleanup-user").email("cleanup@example.com").build();
        user.setId(userId);

        UserMfaEnrollment existing = UserMfaEnrollment.builder()
            .user(user)
            .method(MfaMethodType.EMAIL)
            .enabled(true)
            .primaryFactor(false)
            .build();
        existing.setId(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mfaEnrollmentRepository.findByUserId(userId)).thenReturn(List.of(existing));
        when(mfaEnrollmentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.upsertMfaEnrollments(userId, null);

        assertThat(response).isEmpty();
        verify(mfaEnrollmentRepository).delete(existing);
    }

    @Test
    void upsertRecoveryContactsMaintainsPrimaryFlag() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("contact-user").email("contact@example.com").build();
        user.setId(userId);

        UserRecoveryContact existing = UserRecoveryContact.builder()
            .user(user)
            .contactType(RecoveryContactType.EMAIL)
            .contactValue("legacy@example.com")
            .primaryContact(true)
            .verified(true)
            .build();
        existing.setId(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(recoveryContactRepository.findByUserId(userId)).thenReturn(List.of(existing));
        when(recoveryContactRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(recoveryContactRepository).delete(existing);

        UserRecoveryContactRequestDTO request = new UserRecoveryContactRequestDTO();
        request.setContactType(RecoveryContactType.PHONE);
        request.setContactValue("+10987654321");
        request.setPrimaryContact(true);
        request.setVerified(true);

        var result = service.upsertRecoveryContacts(userId, List.of(request));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isPrimaryContact()).isTrue();
        verify(recoveryContactRepository).delete(existing);
    }

    @Test
    void upsertRecoveryContactsUpdatesVerifiedTimestamp() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("verified-user").email("verified@example.com").build();
        user.setId(userId);

        UserRecoveryContact existing = UserRecoveryContact.builder()
            .user(user)
            .contactType(RecoveryContactType.EMAIL)
            .contactValue("existing@example.com")
            .verified(true)
            .verifiedAt(LocalDateTime.now().minusDays(3))
            .build();
        existing.setId(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(recoveryContactRepository.findByUserId(userId)).thenReturn(List.of(existing));

        List<UserRecoveryContact> persisted = new ArrayList<>();
        when(recoveryContactRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<UserRecoveryContact> argument = invocation.getArgument(0);
            persisted.clear();
            persisted.addAll(argument);
            return argument;
        });

        UserRecoveryContactRequestDTO update = new UserRecoveryContactRequestDTO();
        update.setContactType(RecoveryContactType.EMAIL);
        update.setContactValue("  existing@example.com  ");
        update.setVerified(false);
        update.setPrimaryContact(false);

        var result = service.upsertRecoveryContacts(userId, List.of(update));

        assertThat(result).hasSize(1);
        assertThat(persisted).hasSize(1);
        UserRecoveryContact saved = persisted.get(0);
        assertThat(saved.isVerified()).isFalse();
        assertThat(saved.getVerifiedAt()).isNull();
        assertThat(saved.getContactValue()).isEqualTo("existing@example.com");
        verify(recoveryContactRepository, never()).delete(existing);
    }
}
