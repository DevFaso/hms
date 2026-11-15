package com.example.hms.service;

import com.example.hms.enums.MfaMethodType;
import com.example.hms.enums.RecoveryContactType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.NotificationTransportUnavailableException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.User;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.model.UserRecoveryContact;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentRequestDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactRequestDTO;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.repository.UserRecoveryContactRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.integration.IkoddiGateway;
import com.example.hms.service.integration.SmsRecipientNotAllowedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailService emailService;

    @Mock
    private MfaService mfaService;

    @Mock
    private AuditEventLogService auditEventLogService;

    @Mock
    private IkoddiGateway smsGateway;

    @Mock
    private MessageSource messageSource;

    /** Fixed instant: the recovery-code expiry is written and checked against the injected clock. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 10, 0);

    @Spy
    private Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @InjectMocks
    private UserCredentialLifecycleServiceImpl service;

    private UserRecoveryContact pendingContact(UUID userId) {
        User owner = User.builder().username("owner").email("owner@example.com").passwordHash("hash").build();
        owner.setId(userId);
        UserRecoveryContact contact = UserRecoveryContact.builder()
            .user(owner)
            .contactType(RecoveryContactType.EMAIL)
            .contactValue("backup@example.com")
            .build();
        contact.setId(UUID.randomUUID());
        return contact;
    }

    @Test
    void someoneElsesRecoveryContactAnswersAsAnUnknownOne() {
        UUID caller = UUID.randomUUID();
        UserRecoveryContact foreign = pendingContact(UUID.randomUUID());
        UUID unknownId = UUID.randomUUID();
        when(recoveryContactRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        when(recoveryContactRepository.findById(unknownId)).thenReturn(Optional.empty());

        for (boolean verify : new boolean[] {false, true}) {
            Throwable asForeign = catchThrowable(() -> {
                if (verify) {
                    service.verifyRecoveryContact(caller, foreign.getId(), "123456");
                } else {
                    service.sendRecoveryContactVerificationCode(caller, foreign.getId());
                }
            });
            Throwable asUnknown = catchThrowable(() -> {
                if (verify) {
                    service.verifyRecoveryContact(caller, unknownId, "123456");
                } else {
                    service.sendRecoveryContactVerificationCode(caller, unknownId);
                }
            });
            assertThat(asForeign).isInstanceOf(ResourceNotFoundException.class)
                .hasSameClassAs(asUnknown);
            assertThat(asForeign.getMessage().replace(foreign.getId().toString(), "<id>"))
                .isEqualTo(asUnknown.getMessage().replace(unknownId.toString(), "<id>"));
        }
        verify(recoveryContactRepository, never()).save(any());
    }

    @Test
    void recoveryCodeExpiryIsStampedFromTheInjectedClock() {
        UUID userId = UUID.randomUUID();
        UserRecoveryContact contact = pendingContact(userId);
        when(recoveryContactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        service.sendRecoveryContactVerificationCode(userId, contact.getId());

        assertThat(contact.getVerificationCodeExpiresAt()).isEqualTo(NOW.plusMinutes(15));
    }

    @Test
    void recoveryCodeIsStillValidAtItsExpiryInstantAndExpiredOneNanosecondLater() {
        UUID userId = UUID.randomUUID();
        UserRecoveryContact contact = pendingContact(userId);
        contact.setVerificationCodeHash("hashed");
        contact.setVerificationCodeExpiresAt(NOW);
        when(recoveryContactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));
        when(passwordEncoder.matches("123456", "hashed")).thenReturn(true);
        when(recoveryContactRepository.save(contact)).thenReturn(contact);

        service.verifyRecoveryContact(userId, contact.getId(), "123456");
        assertThat(contact.isVerified()).isTrue();
        assertThat(contact.getVerifiedAt()).isEqualTo(NOW);

        UserRecoveryContact late = pendingContact(userId);
        late.setVerificationCodeHash("hashed");
        late.setVerificationCodeExpiresAt(NOW.minusNanos(1));
        when(recoveryContactRepository.findById(late.getId())).thenReturn(Optional.of(late));

        UUID lateId = late.getId();
        assertThatThrownBy(() -> service.verifyRecoveryContact(userId, lateId, "123456"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expired");
    }

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
            .lastVerifiedAt(NOW.minusDays(1))
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
        LocalDateTime lastLogin = NOW.minusHours(4);

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
            .lastVerifiedAt(NOW.minusDays(1))
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
            .verifiedAt(NOW.minusDays(2))
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
    void upsertMfaEnrollments_neverTakesLastVerifiedAtFromTheClient_andReportsTheServerRule() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("mfa-user").email("mfa@example.com").build();
        user.setId(userId);
        java.time.LocalDateTime verifiedAt = java.time.LocalDateTime.of(2026, 9, 1, 10, 0);
        UserMfaEnrollment confirmedTotp = UserMfaEnrollment.builder()
            .user(user).method(MfaMethodType.TOTP).enabled(true).verified(true)
            .lastVerifiedAt(verifiedAt).build();
        confirmedTotp.setId(UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mfaEnrollmentRepository.findByUserId(userId)).thenReturn(List.of(confirmedTotp));
        when(mfaEnrollmentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        UserMfaEnrollmentRequestDTO keep = new UserMfaEnrollmentRequestDTO();
        keep.setMethod(MfaMethodType.TOTP);
        keep.setEnabled(true);
        keep.setLastVerifiedAt(null);
        UserMfaEnrollmentRequestDTO forged = new UserMfaEnrollmentRequestDTO();
        forged.setMethod(MfaMethodType.EMAIL);
        forged.setEnabled(true);
        forged.setLastVerifiedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));

        var response = service.upsertMfaEnrollments(userId, List.of(keep, forged));

        assertThat(confirmedTotp.getLastVerifiedAt()).isEqualTo(verifiedAt);
        assertThat(response).filteredOn(d -> d.getMethod() == MfaMethodType.TOTP)
            .singleElement().satisfies(d -> assertThat(d.isActive()).isTrue());
        assertThat(response).filteredOn(d -> d.getMethod() == MfaMethodType.EMAIL)
            .singleElement().satisfies(d -> {
                assertThat(d.getLastVerifiedAt()).isNull();
                assertThat(d.isActive()).isFalse();
            });
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
    void upsertOwnMfaEnrollments_aRefusedStepUpChangesNothing() {
        UUID userId = UUID.randomUUID();
        doThrow(new com.example.hms.exception.MfaStepUpRefusedException(
                com.example.hms.exception.MfaStepUpRefusedException.Reason.REQUIRED))
            .when(mfaService).requireStepUpToChangeAuthenticator(userId, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.upsertOwnMfaEnrollments(userId, null, null, null))
            .isInstanceOf(com.example.hms.exception.MfaStepUpRefusedException.class);

        verifyNoInteractions(mfaEnrollmentRepository);
    }

    @Test
    void upsertOwnMfaEnrollments_runsTheUpsertOnceTheStepUpPasses() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("own-user").email("own@example.com").build();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mfaEnrollmentRepository.findByUserId(userId)).thenReturn(List.of());
        when(mfaEnrollmentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.upsertOwnMfaEnrollments(userId, null, null, "123456")).isEmpty();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mfaService, mfaEnrollmentRepository);
        order.verify(mfaService).requireStepUpToChangeAuthenticator(userId, null, "123456");
        order.verify(mfaEnrollmentRepository).findByUserId(userId);
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
    void upsertRecoveryContactsPreservesExistingVerifiedState() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("verified-user").email("verified@example.com").build();
        user.setId(userId);

        LocalDateTime verifiedTime = NOW.minusDays(3);
        UserRecoveryContact existing = UserRecoveryContact.builder()
            .user(user)
            .contactType(RecoveryContactType.EMAIL)
            .contactValue("existing@example.com")
            .verified(true)
            .verifiedAt(verifiedTime)
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

        // Client sends verified=false but service should preserve existing state
        UserRecoveryContactRequestDTO update = new UserRecoveryContactRequestDTO();
        update.setContactType(RecoveryContactType.EMAIL);
        update.setContactValue("  existing@example.com  ");
        update.setVerified(false);
        update.setPrimaryContact(false);

        var result = service.upsertRecoveryContacts(userId, List.of(update));

        assertThat(result).hasSize(1);
        assertThat(persisted).hasSize(1);
        UserRecoveryContact saved = persisted.get(0);
        // Verified state preserved — only the verify endpoint can change it
        assertThat(saved.isVerified()).isTrue();
        assertThat(saved.getVerifiedAt()).isEqualTo(verifiedTime);
        assertThat(saved.getContactValue()).isEqualTo("existing@example.com");
        verify(recoveryContactRepository, never()).delete(existing);
    }

    @Test
    void upsertRecoveryContactsNewContactStartsUnverified() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("new-user").email("new@example.com").build();
        user.setId(userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(recoveryContactRepository.findByUserId(userId)).thenReturn(List.of());

        List<UserRecoveryContact> persisted = new ArrayList<>();
        when(recoveryContactRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<UserRecoveryContact> argument = invocation.getArgument(0);
            persisted.clear();
            persisted.addAll(argument);
            return argument;
        });

        // Client sends verified=true but new contacts must start unverified
        UserRecoveryContactRequestDTO request = new UserRecoveryContactRequestDTO();
        request.setContactType(RecoveryContactType.EMAIL);
        request.setContactValue("recovery@example.com");
        request.setVerified(true);
        request.setPrimaryContact(true);

        var result = service.upsertRecoveryContacts(userId, List.of(request));

        assertThat(result).hasSize(1);
        assertThat(persisted).hasSize(1);
        UserRecoveryContact saved = persisted.get(0);
        assertThat(saved.isVerified()).isFalse();
        assertThat(saved.getVerifiedAt()).isNull();
    }

    // ── Round 4: a super-admin's change to any account's MFA needs the ACTOR's step-up ──

    @Test
    void aSuperAdminChange_needsTheActorsStepUp_andARefusalChangesNothing() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = User.builder().username("target").email("t@example.com").build();
        target.setId(targetId);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(mfaService.requireStepUp(eq(actorId), eq("000000"), any(), eq(false)))
            .thenThrow(new com.example.hms.exception.MfaStepUpRefusedException(
                com.example.hms.exception.MfaStepUpRefusedException.Reason.INVALID));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.upsertMfaEnrollmentsAsSuperAdmin(actorId, targetId, List.of(), "000000"))
            .isInstanceOf(com.example.hms.exception.MfaStepUpRefusedException.class);

        verify(mfaEnrollmentRepository, never()).saveAll(anyList());
        verify(mfaEnrollmentRepository, never()).delete(any());
    }

    @Test
    void aSuperAdminChangeWithoutAnActiveFactor_inNonStrictMode_isAuditedAsABypass() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = User.builder().username("target").email("t@example.com").build();
        target.setId(targetId);
        when(mfaService.requireStepUp(eq(actorId), eq(null), any(), eq(false))).thenReturn(false);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(mfaEnrollmentRepository.findByUserId(targetId)).thenReturn(List.of());
        when(mfaEnrollmentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.upsertMfaEnrollmentsAsSuperAdmin(actorId, targetId, List.of(), null);

        verify(auditEventLogService).logEvent(org.mockito.ArgumentMatchers.argThat(e ->
            e.getEventType() == com.example.hms.enums.AuditEventType.SECURITY_ALERT_TRIGGERED
                && actorId.equals(e.getUserId())));
    }

    @Test
    void twoTotpEntries_areABadRequest_notAUniqueIndexViolation() {
        UUID userId = UUID.randomUUID();
        UserMfaEnrollmentRequestDTO first = new UserMfaEnrollmentRequestDTO();
        first.setMethod(MfaMethodType.TOTP);
        first.setChannel("a");
        UserMfaEnrollmentRequestDTO second = new UserMfaEnrollmentRequestDTO();
        second.setMethod(MfaMethodType.TOTP);
        second.setChannel("b");
        User user = User.builder().username("u").email("u@example.com").build();
        user.setId(userId);
        org.mockito.Mockito.lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        List<UserMfaEnrollmentRequestDTO> twoTotp = List.of(first, second);
        assertThatThrownBy(() -> service.upsertMfaEnrollments(userId, twoTotp))
            .isInstanceOf(IllegalArgumentException.class);
        verify(mfaEnrollmentRepository, never()).saveAll(anyList());
    }

    @Test
    void aDisabledTotpAuthenticator_isNeverSwitchedBackOnByTheList() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("mfa-user").email("mfa@example.com").build();
        user.setId(userId);
        UserMfaEnrollment totp = UserMfaEnrollment.builder()
            .user(user).method(MfaMethodType.TOTP).verified(true).enabled(false).build();
        totp.setId(UUID.randomUUID());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mfaEnrollmentRepository.findByUserId(userId)).thenReturn(List.of(totp));
        when(mfaEnrollmentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        UserMfaEnrollmentRequestDTO request = new UserMfaEnrollmentRequestDTO();
        request.setMethod(MfaMethodType.TOTP);
        request.setEnabled(true);

        service.upsertMfaEnrollments(userId, List.of(request));

        // Only confirming a code turns it on: no old secret revived without one.
        assertThat(totp.isEnabled()).isFalse();
    }

    @Test
    void aTotpRowWithAChangedChannel_isUpdatedInPlace_neverInsertedBesideTheOld() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("mfa-user").email("mfa@example.com").build();
        user.setId(userId);
        UserMfaEnrollment totp = UserMfaEnrollment.builder()
            .user(user).method(MfaMethodType.TOTP).channel("old-device").enabled(true).build();
        totp.setId(UUID.randomUUID());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mfaEnrollmentRepository.findByUserId(userId)).thenReturn(List.of(totp));
        when(mfaEnrollmentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        UserMfaEnrollmentRequestDTO request = new UserMfaEnrollmentRequestDTO();
        request.setMethod(MfaMethodType.TOTP);
        request.setChannel("new-device");
        request.setEnabled(true);

        var response = service.upsertMfaEnrollments(userId, List.of(request));

        // One TOTP row per user (V183's unique index): the same row, renamed.
        assertThat(response).hasSize(1);
        assertThat(totp.getChannel()).isEqualTo("new-device");
        verify(mfaEnrollmentRepository, never()).delete(any());
    }

    @Test
    void anUnknownTarget_orTwoTotpEntries_spendNoStepUp() {
        // The step-up commits on its own (a backup code is spent): a request
        // that fails anyway must fail before it.
        UUID actorId = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(userRepository.findById(missing)).thenReturn(Optional.empty());

        List<UserMfaEnrollmentRequestDTO> none = List.of();
        assertThatThrownBy(() -> service.upsertMfaEnrollmentsAsSuperAdmin(actorId, missing, none, "12345678"))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);

        UUID targetId = UUID.randomUUID();
        User target = User.builder().username("target").email("t@example.com").build();
        target.setId(targetId);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        UserMfaEnrollmentRequestDTO first = new UserMfaEnrollmentRequestDTO();
        first.setMethod(MfaMethodType.TOTP);
        first.setChannel("a");
        UserMfaEnrollmentRequestDTO second = new UserMfaEnrollmentRequestDTO();
        second.setMethod(MfaMethodType.TOTP);
        second.setChannel("b");

        List<UserMfaEnrollmentRequestDTO> twoTotp = List.of(first, second);
        assertThatThrownBy(() -> service.upsertMfaEnrollmentsAsSuperAdmin(actorId, targetId, twoTotp, "12345678"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(mfaService, never()).requireStepUp(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void anEntryListedTwice_isABadRequest_beforeAnyStepUp() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = User.builder().username("target").email("t@example.com").build();
        target.setId(targetId);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        UserMfaEnrollmentRequestDTO sms = new UserMfaEnrollmentRequestDTO();
        sms.setMethod(MfaMethodType.SMS);
        sms.setChannel("+22670000000");
        UserMfaEnrollmentRequestDTO same = new UserMfaEnrollmentRequestDTO();
        same.setMethod(MfaMethodType.SMS);
        same.setChannel(" +22670000000 ");
        List<UserMfaEnrollmentRequestDTO> twice = List.of(sms, same);

        assertThatThrownBy(() -> service.upsertMfaEnrollmentsAsSuperAdmin(actorId, targetId, twice, "12345678"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(mfaService, never()).requireStepUp(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void everyRefusedRecoveryVerificationCarriesItsMessageKey() {
        UUID userId = UUID.randomUUID();

        UserRecoveryContact verified = pendingContact(userId);
        verified.setVerified(true);
        UserRecoveryContact noCode = pendingContact(userId);
        UserRecoveryContact exhausted = pendingContact(userId);
        exhausted.setVerificationCodeHash("hashed");
        exhausted.setVerificationAttempts(99);
        UserRecoveryContact wrongCode = pendingContact(userId);
        wrongCode.setVerificationCodeHash("hashed");
        wrongCode.setVerificationCodeExpiresAt(NOW.plusMinutes(5));
        when(passwordEncoder.matches("000000", "hashed")).thenReturn(false);

        List<String> messages = new ArrayList<>();
        for (UserRecoveryContact contact : List.of(verified, noCode, exhausted, wrongCode)) {
            when(recoveryContactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));
            UUID contactId = contact.getId();
            Throwable thrown = catchThrowable(() -> service.verifyRecoveryContact(userId, contactId, "000000"));
            assertThat(thrown).isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
            messages.add(thrown.getMessage());
        }
        assertThat(messages).containsExactly("users.recovery.alreadyVerified", "users.verification.noCodeSent",
            "users.verification.tooManyAttempts", "users.verification.invalidCode");
        assertThat(wrongCode.isVerified()).isFalse();
    }

    // ── D2000: a PHONE recovery contact gets its code by SMS, or a clear refusal ──

    private UserRecoveryContact pendingPhoneContact(UUID userId, String number) {
        UserRecoveryContact contact = pendingContact(userId);
        contact.setContactType(RecoveryContactType.PHONE);
        contact.setContactValue(number);
        return contact;
    }

    @Test
    void phoneRecoveryCodeIsSentBySmsInFrenchToTheGatewayNumber() {
        UUID userId = UUID.randomUUID();
        UserRecoveryContact contact = pendingPhoneContact(userId, "70 12 34 56");
        when(recoveryContactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(smsGateway.isConfigured()).thenReturn(true);
        when(messageSource.getMessage(eq("sms.phoneVerification.code"), any(Object[].class), eq(Locale.FRENCH)))
            .thenReturn("e-Keneya : votre code");
        when(smsGateway.sendSms(anyList(), anyString(), anyString()))
            .thenReturn(new IkoddiGateway.SmsDispatch(true, "{}"));

        String type = service.sendRecoveryContactVerificationCode(userId, contact.getId());

        assertThat(type).isEqualTo("PHONE");
        verify(smsGateway).sendSms(List.of("+22670123456"), "e-Keneya : votre code",
            UserCredentialLifecycleServiceImpl.RECOVERY_SMS_CAMPAIGN);
        // The texted code is the one whose hash was stored, with the 15-minute lifetime.
        ArgumentCaptor<String> hashed = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(hashed.capture());
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(messageSource).getMessage(eq("sms.phoneVerification.code"), args.capture(), eq(Locale.FRENCH));
        assertThat(args.getValue()).containsExactly(hashed.getValue(), 15);
        assertThat(hashed.getValue()).matches("\\d{6}");
        verify(emailService, never()).sendRecoveryContactVerificationEmail(anyString(), anyString());
    }

    @Test
    void phoneRecoveryCodeIsRefusedBeforeAnyCodeExistsWhenNoSmsTransportIsConfigured() {
        UUID userId = UUID.randomUUID();
        UserRecoveryContact contact = pendingPhoneContact(userId, "+22670123456");
        when(recoveryContactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));
        when(smsGateway.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.sendRecoveryContactVerificationCode(userId, contact.getId()))
            .isInstanceOf(NotificationTransportUnavailableException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("users.recovery.smsUnavailable"));

        verify(smsGateway, never()).sendSms(anyList(), anyString(), anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(recoveryContactRepository, never()).save(any());
        assertThat(contact.getVerificationCodeHash()).isNull();
    }

    @Test
    void phoneRecoveryCodeThatTheProviderRefusesIsAnErrorNotASuccess() {
        UUID userId = UUID.randomUUID();
        UserRecoveryContact contact = pendingPhoneContact(userId, "+22670123456");
        when(recoveryContactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(smsGateway.isConfigured()).thenReturn(true);
        when(messageSource.getMessage(anyString(), any(Object[].class), any(Locale.class))).thenReturn("body");
        when(smsGateway.sendSms(anyList(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("sms.orange.rejected"))
            .thenThrow(new SmsRecipientNotAllowedException())
            .thenReturn(new IkoddiGateway.SmsDispatch(false, "{}"))
            .thenThrow(new NotificationTransportUnavailableException("Text messaging is unavailable right now."));

        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            contact.setVerificationCodeExpiresAt(null); // what the rollback of the refused send restores
            Throwable thrown = catchThrowable(() -> service.sendRecoveryContactVerificationCode(userId, contact.getId()));
            assertThat(thrown).isInstanceOf(BusinessException.class);
            keys.add(((BusinessException) thrown).getMessageKey());
        }
        assertThat(keys).containsExactly("users.recovery.smsFailed", "sms.recipient.notAllowlisted",
            "users.recovery.smsFailed");
        contact.setVerificationCodeExpiresAt(null);
        assertThatThrownBy(() -> service.sendRecoveryContactVerificationCode(userId, contact.getId()))
            .isInstanceOf(NotificationTransportUnavailableException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("users.recovery.smsUnavailable"));
    }

    @Test
    void phoneRecoveryCodeToANumberWeDoNotTextIsRefusedOnTheField() {
        UUID userId = UUID.randomUUID();
        UserRecoveryContact contact = pendingPhoneContact(userId, "+1 202 555 0143");
        when(recoveryContactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));
        when(smsGateway.isConfigured()).thenReturn(true);

        assertThatThrownBy(() -> service.sendRecoveryContactVerificationCode(userId, contact.getId()))
            .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                assertThat(ex.getField()).isEqualTo("contactValue");
                assertThat(ex.getMessageKey()).isEqualTo("users.recovery.phoneInvalid");
            });
        verify(smsGateway, never()).sendSms(anyList(), anyString(), anyString());
        verify(recoveryContactRepository, never()).save(any());
    }

    @Test
    void emailRecoveryCodeWithNoMailTransportIsALocalizedRefusal() {
        UUID userId = UUID.randomUUID();
        UserRecoveryContact contact = pendingContact(userId);
        when(recoveryContactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        doThrow(new NotificationTransportUnavailableException("Mail transport is not configured on this deployment"))
            .when(emailService).sendRecoveryContactVerificationEmail(anyString(), anyString());

        assertThatThrownBy(() -> service.sendRecoveryContactVerificationCode(userId, contact.getId()))
            .isInstanceOf(NotificationTransportUnavailableException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("users.recovery.emailUnavailable"));
    }

    /**
     * D2000 class guard over every contact type: "code sent" is answered only
     * when exactly one transport was handed the code; any other type is
     * refused before a code is stored. A new enum value cannot fall through.
     */
    @ParameterizedTest
    @EnumSource(RecoveryContactType.class)
    void everyContactTypeEitherReallySendsItsCodeOrIsRefusedBeforeOneExists(RecoveryContactType type) {
        UUID userId = UUID.randomUUID();
        UserRecoveryContact contact = pendingPhoneContact(userId,
            type == RecoveryContactType.EMAIL ? "backup@example.com" : "+22670123456");
        contact.setContactType(type);
        when(recoveryContactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));
        org.mockito.Mockito.lenient().when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        org.mockito.Mockito.lenient().when(smsGateway.isConfigured()).thenReturn(true);
        org.mockito.Mockito.lenient().when(messageSource.getMessage(anyString(), any(Object[].class), any(Locale.class)))
            .thenReturn("body");
        org.mockito.Mockito.lenient().when(smsGateway.sendSms(anyList(), anyString(), anyString()))
            .thenReturn(new IkoddiGateway.SmsDispatch(true, "{}"));

        Throwable thrown = catchThrowable(() -> service.sendRecoveryContactVerificationCode(userId, contact.getId()));

        int emails = org.mockito.Mockito.mockingDetails(emailService).getInvocations().size();
        int texts = (int) org.mockito.Mockito.mockingDetails(smsGateway).getInvocations().stream()
            .filter(inv -> inv.getMethod().getName().equals("sendSms")).count();
        if (thrown == null) {
            assertThat(emails + texts).as("%s answered 'code sent'; exactly one transport must carry it", type)
                .isEqualTo(1);
        } else {
            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getMessageKey()).isEqualTo("users.recovery.typeNotVerifiable");
            verify(recoveryContactRepository, never()).save(any());
            assertThat(emails + texts).isZero();
        }
        assertThat(thrown == null).as("only EMAIL and PHONE can carry a code")
            .isEqualTo(type == RecoveryContactType.EMAIL || type == RecoveryContactType.PHONE);
    }

    // ── D2000 (review): a paid code is throttled like the desk's phone verification ──

    private void stubAcceptedSms() {
        org.mockito.Mockito.lenient().when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        org.mockito.Mockito.lenient().when(smsGateway.isConfigured()).thenReturn(true);
        org.mockito.Mockito.lenient().when(messageSource.getMessage(anyString(), any(Object[].class), any(Locale.class)))
            .thenReturn("body");
        org.mockito.Mockito.lenient().when(smsGateway.sendSms(anyList(), anyString(), anyString()))
            .thenReturn(new IkoddiGateway.SmsDispatch(true, "{}"));
    }

    @Test
    void aSecondCodeForTheSameContactWithinTheCooldownIs429AndNotSent() {
        UUID userId = UUID.randomUUID();
        UserRecoveryContact contact = pendingPhoneContact(userId, "+22670123456");
        when(recoveryContactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));
        stubAcceptedSms();

        service.sendRecoveryContactVerificationCode(userId, contact.getId());
        Throwable again = catchThrowable(() -> service.sendRecoveryContactVerificationCode(userId, contact.getId()));

        assertThat(again).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(429);
            assertThat(ex.getReason()).isEqualTo("users.recovery.cooldown");
        });
        verify(smsGateway, org.mockito.Mockito.times(1)).sendSms(anyList(), anyString(), anyString());

        // Past the desk's cooldown (60 s) the contact can be sent a fresh code.
        contact.setVerificationCodeExpiresAt(NOW.plusMinutes(15)
            .minusSeconds(PhoneVerificationServiceImpl.RESEND_COOLDOWN_SECONDS));
        service.sendRecoveryContactVerificationCode(userId, contact.getId());
        verify(smsGateway, org.mockito.Mockito.times(2)).sendSms(anyList(), anyString(), anyString());
    }

    @Test
    void theHourlyCapPerUserIsTheDesksAndAnswers429() {
        UUID userId = UUID.randomUUID();
        stubAcceptedSms();
        int cap = PhoneVerificationServiceImpl.MAX_SENDS_PER_USER_PER_HOUR;
        for (int i = 0; i <= cap; i++) {
            UserRecoveryContact contact = pendingPhoneContact(userId, "+2267012345" + (i % 10));
            when(recoveryContactRepository.findById(contact.getId())).thenReturn(Optional.of(contact));
            Throwable thrown = catchThrowable(() -> service.sendRecoveryContactVerificationCode(userId, contact.getId()));
            if (i < cap) {
                assertThat(thrown).as("send %d", i + 1).isNull();
            } else {
                assertThat(thrown).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                    ex -> {
                        assertThat(ex.getStatusCode().value()).isEqualTo(429);
                        assertThat(ex.getReason()).isEqualTo("users.recovery.limitReached");
                    });
            }
        }
        verify(smsGateway, org.mockito.Mockito.times(cap)).sendSms(anyList(), anyString(), anyString());
        // Another user is not held by this one's cap.
        UUID other = UUID.randomUUID();
        UserRecoveryContact theirs = pendingPhoneContact(other, "+22670999999");
        when(recoveryContactRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        service.sendRecoveryContactVerificationCode(other, theirs.getId());
    }

    // ── D2000 (owner request): a recovery contact is not the sign-in email or phone ──

    @Test
    void aRecoveryContactEqualToTheSignInEmailOrPhoneIsRefused() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("u").email("Owner@Example.com").phoneNumber("+226 70 12 34 56").build();
        user.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(recoveryContactRepository.findByUserId(userId)).thenReturn(List.of());

        for (String[] typed : new String[][] {{"EMAIL", "  owner@EXAMPLE.com "}, {"PHONE", "70123456"},
            {"PHONE", "0022670123456"}}) {
            UserRecoveryContactRequestDTO request = new UserRecoveryContactRequestDTO();
            request.setContactType(RecoveryContactType.valueOf(typed[0]));
            request.setContactValue(typed[1]);
            assertThatThrownBy(() -> service.upsertRecoveryContacts(userId, List.of(request)))
                .as(typed[1])
                .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                    assertThat(ex.getField()).isEqualTo("contactValue");
                    assertThat(ex.getMessageKey()).isEqualTo("users.recovery.sameAsSignIn");
                });
        }
        verify(recoveryContactRepository, never()).saveAll(anyList());
    }

    @Test
    void aDifferentContactIsAcceptedAndOneAlreadyOnFileStaysRemovable() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("u").email("owner@example.com").phoneNumber("+22670123456").build();
        user.setId(userId);
        UserRecoveryContact legacy = UserRecoveryContact.builder().user(user)
            .contactType(RecoveryContactType.EMAIL).contactValue("owner@example.com").build();
        legacy.setId(UUID.randomUUID());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(recoveryContactRepository.findByUserId(userId)).thenReturn(List.of(legacy));
        when(recoveryContactRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        UserRecoveryContactRequestDTO keep = new UserRecoveryContactRequestDTO();
        keep.setContactType(RecoveryContactType.EMAIL);
        keep.setContactValue("owner@example.com");
        UserRecoveryContactRequestDTO other = new UserRecoveryContactRequestDTO();
        other.setContactType(RecoveryContactType.PHONE);
        other.setContactValue("+22676000000");

        assertThat(service.upsertRecoveryContacts(userId, List.of(keep, other))).hasSize(2);
    }

    @Test
    void credentialHealthSaysWhetherAPhoneRecoveryContactCanBeVerifiedHere() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().username("u").email("owner@example.com").build();
        user.setId(userId);
        when(userRepository.findByIdWithRolesAndProfiles(userId)).thenReturn(Optional.of(user));
        when(mfaEnrollmentRepository.findByUserId(userId)).thenReturn(List.of());
        when(recoveryContactRepository.findByUserId(userId)).thenReturn(List.of());
        when(smsGateway.isConfigured()).thenReturn(false, true);

        assertThat(service.getCredentialHealth(userId).isPhoneRecoveryAvailable()).isFalse();
        assertThat(service.getCredentialHealth(userId).isPhoneRecoveryAvailable()).isTrue();
    }
}
