package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.PasswordResetToken;
import com.example.hms.model.User;
import com.example.hms.repository.PasswordResetTokenRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Locale;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private EmailService emailService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private com.example.hms.security.LoginAttemptService loginAttemptService;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private SmsService smsService;
    @Mock private org.springframework.context.MessageSource messageSource;
    @Mock private org.springframework.core.env.Environment environment;

    /** Fixed instant: token validity and cleanup are computed against the injected clock. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 10, 0);
    @Spy private Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @InjectMocks private PasswordResetServiceImpl service;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setEmail("test@test.com");
        user.setUsername("testuser");
    }

    @Test
    void requestReset_success() {
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestReset("test@test.com", Locale.ENGLISH);

        verify(tokenRepository).deleteByUser_IdAndConsumedAtIsNull(userId);
        verify(tokenRepository).save(any(PasswordResetToken.class));
        verify(emailService).sendPasswordResetEmail(eq("test@test.com"), anyString());
    }

    @Test
    void requestReset_withIp_success() {
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestReset("test@test.com", Locale.ENGLISH, "127.0.0.1");

        verify(tokenRepository).save(any(PasswordResetToken.class));
        verify(emailService).sendPasswordResetEmail(eq("test@test.com"), anyString());
    }

    @Test
    void qaD171_aPhoneOnlyAccountGetsItsResetLinkBySms() {
        user.setEmail(null);
        user.setPhoneNumber("+22670202020");
        when(userRepository.findByPhoneNumber("+22670202020")).thenReturn(Optional.of(user));
        when(smsService.deliversRealSms()).thenReturn(true);
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageSource.getMessage(eq("sms.passwordReset.link"), any(), any(Locale.class))).thenReturn("link");

        service.requestResetByPhone("70 20 20 20", Locale.FRENCH, "127.0.0.1");

        verify(tokenRepository).save(any(PasswordResetToken.class));
        verify(smsService).send("+22670202020", "link");
        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    void qaD222_aProviderFailureNeverReachesTheCaller() {
        user.setEmail(null);
        user.setPhoneNumber("+22670202020");
        when(userRepository.findByPhoneNumber("+22670202020")).thenReturn(Optional.of(user));
        when(smsService.deliversRealSms()).thenReturn(true);
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageSource.getMessage(eq("sms.passwordReset.link"), any(), any(Locale.class))).thenReturn("link");
        doThrow(new IllegalStateException("IKODDI SMS send request failed with status 400"))
            .when(smsService).send(any(), any());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> service.requestResetByPhone("70202020", Locale.FRENCH, null));
    }

    /**
     * D2000 class (prod 2026-11-15, Orange 401 on the token): a refused SMS logged
     * "failed" and then "issued by SMS". The success line and the audit come only
     * after the provider accepted; the caller's answer is the same either way.
     */
    @Test
    void d2000_aRefusedResetSmsIsNeverLoggedOrAuditedAsIssued() {
        user.setEmail(null);
        user.setPhoneNumber("+22670202020");
        when(userRepository.findByPhoneNumber("+22670202020")).thenReturn(Optional.of(user));
        when(smsService.deliversRealSms()).thenReturn(true);
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageSource.getMessage(eq("sms.passwordReset.link"), any(), any(Locale.class))).thenReturn("link");
        doThrow(new IllegalStateException("sms.orange.authFailed")).when(smsService).send(any(), any());

        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PasswordResetServiceImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.requestResetByPhone("70202020", Locale.FRENCH, null));
        } finally {
            logger.detachAppender(appender);
        }

        java.util.List<String> lines = appender.list.stream()
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).noneMatch(l -> l.contains("issued by SMS"));
        assertThat(lines).anyMatch(l -> l.contains("SMS NOT sent for user " + userId));
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    void d2000_anAcceptedResetSmsIsLoggedAndAuditedAsIssued() {
        user.setEmail(null);
        user.setPhoneNumber("+22670202020");
        when(userRepository.findByPhoneNumber("+22670202020")).thenReturn(Optional.of(user));
        when(smsService.deliversRealSms()).thenReturn(true);
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageSource.getMessage(eq("sms.passwordReset.link"), any(), any(Locale.class))).thenReturn("link");

        service.requestResetByPhone("70202020", Locale.FRENCH, null);

        verify(auditEventLogService).logEvent(any());
    }

    @Test
    void qaD222_aMailFailureNeverReachesTheCaller() {
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new IllegalStateException("smtp down"))
            .when(emailService).sendPasswordResetEmail(any(), any());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> service.requestReset("test@test.com", Locale.ENGLISH, null));
    }

    @Test
    void qaD171_noTokenIsIssuedWithoutARealSmsTransport() {
        user.setPhoneNumber("+22670202020");
        when(userRepository.findByPhoneNumber("+22670202020")).thenReturn(Optional.of(user));
        when(smsService.deliversRealSms()).thenReturn(false);

        service.requestResetByPhone("70202020", Locale.FRENCH, null);

        verify(tokenRepository, never()).save(any());
        verify(smsService, never()).send(any(), any());
    }

    @Test
    void qaD171_anUnknownPhoneIsSilent() {
        when(userRepository.findByPhoneNumber("+22670202020")).thenReturn(Optional.empty());

        service.requestResetByPhone("70202020", Locale.FRENCH, null);

        verify(tokenRepository, never()).save(any());
    }

    // ---- A set-password link for an account activated without seeing its first password ----

    @Test
    void sendSetPasswordLink_goesByEmailToAnAccountWithAnAddress() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendSetPasswordLink(userId, Locale.FRENCH);

        verify(tokenRepository).save(any(PasswordResetToken.class));
        verify(emailService).sendPasswordResetEmail(eq("test@test.com"), anyString());
        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    void sendSetPasswordLink_goesBySmsToAPhoneOnlyAccount() {
        phoneOnly();
        smsTransport(true);
        smsBody();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.sendSetPasswordLink(userId, Locale.FRENCH);

        // The SMS service is a mock: nothing is really sent.
        verify(smsService).send("+22670202020", "link");
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    void sendSetPasswordLink_issuesNothingWithoutAChannel() {
        user.setEmail(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.sendSetPasswordLink(userId, Locale.FRENCH);

        verify(tokenRepository, never()).save(any());
    }

    // ---- QA D787: e-mail OR username OR phone, through the one identifier ----

    private void phoneOnly() {
        user.setEmail(null);
        user.setPhoneNumber("+22670202020");
    }

    private void smsTransport(boolean real) {
        when(smsService.deliversRealSms()).thenReturn(real);
    }

    private void smsBody() {
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageSource.getMessage(eq("sms.passwordReset.link"), any(), any(Locale.class))).thenReturn("link");
    }

    @Test
    void qaD787_thePhoneOnlyPatientOnDevGetsNothingIssuedAndNoError() {
        // PATIENT_A3 on dev: a phone, no e-mail, and no SMS transport configured.
        phoneOnly();
        when(userRepository.findByPhoneNumber("+22670202020")).thenReturn(Optional.of(user));
        smsTransport(false);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> service.requestResetByIdentifier("70 20 20 20", Locale.FRENCH, "127.0.0.1"));

        verify(tokenRepository, never()).save(any());
        verify(smsService, never()).send(any(), any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    void qaD787_aPhoneNumberGetsTheLinkBySmsWhenATransportIsConfigured() {
        phoneOnly();
        when(userRepository.findByPhoneNumber("+22670202020")).thenReturn(Optional.of(user));
        smsTransport(true);
        smsBody();

        service.requestResetByIdentifier("+226 70 20 20 20", Locale.FRENCH, null);

        verify(smsService).send("+22670202020", "link");
        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    void qaD787_aUsernameReachesAPhoneOnlyAccountBySms() {
        phoneOnly();
        when(userRepository.findByUsernameIgnoreCase("patient_a3")).thenReturn(Optional.of(user));
        smsTransport(true);
        smsBody();

        service.requestResetByIdentifier("patient_a3", Locale.FRENCH, null);

        verify(smsService).send("+22670202020", "link");
    }

    @Test
    void qaD787_aUsernameReachesAnAccountWithAnAddressByEmail() {
        when(userRepository.findByUsernameIgnoreCase("testuser")).thenReturn(Optional.of(user));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestResetByIdentifier("testuser", Locale.FRENCH, null);

        verify(emailService).sendPasswordResetEmail(eq("test@test.com"), anyString());
        verify(smsService, never()).send(any(), any());
    }

    @Test
    void qaD787_aTypedPhoneFallsBackToTheAddressWhenNoSmsTransport() {
        user.setPhoneNumber("+22670202020");
        when(userRepository.findByPhoneNumber("+22670202020")).thenReturn(Optional.of(user));
        smsTransport(false);
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestResetByIdentifier("70202020", Locale.FRENCH, null);

        verify(emailService).sendPasswordResetEmail(eq("test@test.com"), anyString());
        verify(smsService, never()).send(any(), any());
    }

    @Test
    void qaD787_anAddressStillWorks_andIsCaseInsensitiveThroughTheRepository() {
        when(userRepository.findByEmail("Test@Test.com")).thenReturn(Optional.of(user));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestResetByIdentifier("  Test@Test.com ", Locale.FRENCH, null);

        verify(emailService).sendPasswordResetEmail(eq("test@test.com"), anyString());
    }

    @Test
    void qaD787_anUnknownIdentifierADeletedAccountAndABlankOneAreSilent() {
        when(userRepository.findByUsernameIgnoreCase("nobody")).thenReturn(Optional.empty());
        service.requestResetByIdentifier("nobody", Locale.FRENCH, null);

        user.setDeleted(true);
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        service.requestResetByIdentifier("test@test.com", Locale.FRENCH, null);

        service.requestResetByIdentifier("   ", Locale.FRENCH, null);
        service.requestResetByIdentifier(null, Locale.FRENCH, null);

        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    void qaD787_anIdentifierMatchingTwoAccountsIsSilent() {
        when(userRepository.findByUsernameIgnoreCase("Twin"))
            .thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1, 2));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> service.requestResetByIdentifier("Twin", Locale.FRENCH, null));

        verify(tokenRepository, never()).save(any());
    }

    @Test
    void qaD787_anAccountWithNoChannelIsSilent() {
        user.setEmail(null);
        user.setPhoneNumber(null);
        when(userRepository.findByUsernameIgnoreCase("testuser")).thenReturn(Optional.of(user));

        service.requestResetByIdentifier("testuser", Locale.FRENCH, null);

        verify(tokenRepository, never()).save(any());
    }

    @Test
    void qaD787_aPhoneOnlyAccountCompletesItsReset_noConfirmationMailToANullAddress() {
        phoneOnly();
        smsTransport(true);
        when(messageSource.getMessage(eq("sms.passwordReset.changed"), any(), any(Locale.class))).thenReturn("changed");
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user).tokenHash(hashOf(rawToken)).expiration(NOW.plusHours(1)).build();
        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("newPassword")).thenReturn("encodedPwd");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.confirmReset(rawToken, "newPassword"));

        assertThat(user.getPasswordHash()).isEqualTo("encodedPwd");
        assertThat(resetToken.getConsumedAt()).isNotNull();
        verify(emailService, never()).sendPasswordResetConfirmationEmail(any(), any());
        verify(smsService).send("+22670202020", "changed");
    }

    @Test
    void qaD787_aFailedConfirmationMailNeverUndoesTheReset() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user).tokenHash(hashOf(rawToken)).expiration(NOW.plusHours(1)).build();
        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("newPassword")).thenReturn("encodedPwd");
        doThrow(new IllegalArgumentException("bad address"))
            .when(emailService).sendPasswordResetConfirmationEmail(any(), any());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.confirmReset(rawToken, "newPassword"));

        verify(userRepository).save(user);
        assertThat(resetToken.getConsumedAt()).isNotNull();
    }

    @Test
    void requestReset_userNotFound() {
        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requestReset("unknown@test.com", Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void confirmReset_success() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.plusHours(1))
            .build();

        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("newPassword")).thenReturn("encodedPwd");

        service.confirmReset(rawToken, "newPassword");

        verify(userRepository).save(user);
        verify(tokenRepository).save(resetToken);
        assertThat(user.getPasswordHash()).isEqualTo("encodedPwd");
        assertThat(resetToken.getConsumedAt()).isNotNull();
        // confirmation email must be sent after a successful reset
        verify(emailService).sendPasswordResetConfirmationEmail(eq("test@test.com"), anyString());
    }

    @Test
    void confirmReset_clearsTheLockAndAuditsIdsOnly() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.plusHours(1))
            .build();
        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("newPassword")).thenReturn("encodedPwd");

        service.confirmReset(rawToken, "newPassword");

        // D18: no transaction in a unit test, so the after-commit callback runs inline.
        verify(loginAttemptService).resetAttempts(userId);
        ArgumentCaptor<com.example.hms.payload.dto.AuditEventRequestDTO> audit =
            ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(com.example.hms.enums.AuditEventType.PASSWORD_RESET_COMPLETE);
        assertThat(audit.getValue().getUserId()).isEqualTo(userId);
        assertThat(String.valueOf(audit.getValue())).doesNotContain("newPassword").doesNotContain(rawToken);
    }

    @Test
    void confirmReset_expiredToken_keepsTheLock() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.minusHours(1))
            .build();
        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> service.confirmReset(rawToken, "newPassword"))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .extracting("messageKey").isEqualTo(PasswordResetServiceImpl.TOKEN_INVALID);
        verify(loginAttemptService, never()).resetAttempts(any());
    }

    @Test
    void requestReset_auditsTheRequestByIdOnly() {
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestReset("test@test.com", Locale.ENGLISH);

        ArgumentCaptor<com.example.hms.payload.dto.AuditEventRequestDTO> audit =
            ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(com.example.hms.enums.AuditEventType.PASSWORD_RESET_REQUEST);
        assertThat(audit.getValue().getUserId()).isEqualTo(userId);
        assertThat(String.valueOf(audit.getValue())).doesNotContain("test@test.com");
    }

    @Test
    void confirmReset_sendsConfirmationEmail_withDisplayName() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        user.setFirstName("Alice");
        user.setLastName("Smith");
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.plusHours(1))
            .build();

        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("newPassword")).thenReturn("encodedPwd");

        service.confirmReset(rawToken, "newPassword");

        verify(emailService).sendPasswordResetConfirmationEmail("test@test.com", "Alice Smith");
    }

    @Test
    void confirmReset_sendsConfirmationEmail_withFirstNameOnly() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        user.setFirstName("Alice");
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.plusHours(1))
            .build();

        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("newPassword")).thenReturn("encodedPwd");

        service.confirmReset(rawToken, "newPassword");

        verify(emailService).sendPasswordResetConfirmationEmail("test@test.com", "Alice");
    }

    @Test
    void confirmReset_sendsConfirmationEmail_fallsBackToUsername_whenNoName() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        // no firstName or lastName set on user
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.plusHours(1))
            .build();

        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("newPassword")).thenReturn("encodedPwd");

        service.confirmReset(rawToken, "newPassword");

        // UserDisplayUtil falls back to username when no first/last name is set
        verify(emailService).sendPasswordResetConfirmationEmail("test@test.com", "testuser");
    }

    @Test
    void confirmReset_doesNotSendConfirmationEmail_whenTokenExpired() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.minusHours(1))
            .build();

        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> service.confirmReset(rawToken, "newPwd"))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .extracting("messageKey").isEqualTo(PasswordResetServiceImpl.TOKEN_INVALID);

        // no confirmation email should be sent when the token is already expired
        verify(emailService, never()).sendPasswordResetConfirmationEmail(anyString(), anyString());
    }

    @Test
    void confirmReset_invalidToken() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmReset(rawToken, "newPwd"))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .extracting("messageKey").isEqualTo(PasswordResetServiceImpl.TOKEN_INVALID);
    }

    @Test
    void confirmReset_expiredToken() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.minusHours(1))
            .build();

        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> service.confirmReset(rawToken, "newPwd"))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .extracting("messageKey").isEqualTo(PasswordResetServiceImpl.TOKEN_INVALID);
    }

    @Test
    void confirmReset_spentToken_sameMessageAsUnknown() {
        // QA D2051: a link already used is refused with the unknown-link message, password untouched.
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.plusHours(1))
            .consumedAt(NOW.minusMinutes(5))
            .build();
        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> service.confirmReset(rawToken, "newPassword"))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .extracting("messageKey").isEqualTo(PasswordResetServiceImpl.TOKEN_INVALID);
        verify(userRepository, never()).save(any());
    }

    @Test
    void confirmReset_shortPassword_keepsItsOwnMessageAndTheLinkUnspent() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.plusHours(1))
            .build();
        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> service.confirmReset(rawToken, "short"))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("auth.password.tooShort");
        assertThat(resetToken.getConsumedAt()).isNull();
        verify(userRepository, never()).save(any());
    }

    /**
     * Only the raw token from the link resets a password. The stored value (its
     * SHA-256, 64 hex characters) is refused like any unknown token: it is
     * hashed again, so it matches nothing, and the password is not touched.
     */
    @Test
    void confirmReset_theStoredHashIsNotAToken() {
        String rawToken = "Zm9yLXRoZS1saW5rLW9ubHktMzItYnl0ZXMtb2YtZW50cm9weQ";
        String storedHash = hashOf(rawToken);
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(storedHash)
            .expiration(NOW.plusHours(1))
            .build();
        org.mockito.Mockito.lenient().when(tokenRepository.findByTokenHash(storedHash))
            .thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> service.confirmReset(storedHash, "newPassword"))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .extracting("messageKey").isEqualTo(PasswordResetServiceImpl.TOKEN_INVALID);
        assertThatThrownBy(() -> service.confirmReset(storedHash.toUpperCase(Locale.ROOT), "newPassword"))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .extracting("messageKey").isEqualTo(PasswordResetServiceImpl.TOKEN_INVALID);
        assertThat(service.verifyToken(storedHash)).isFalse();

        // What was looked up is the hash of what was presented, never the presented value itself.
        verify(tokenRepository, never()).findByTokenHash(storedHash);
        verify(tokenRepository, org.mockito.Mockito.atLeastOnce()).findByTokenHash(hashOf(storedHash));
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
        assertThat(resetToken.getConsumedAt()).isNull();
    }

    @Test
    void confirmReset_setsTheHoldersOwnPassword_nothingTemporaryLeft() {
        String rawToken = "Zm9yLXRoZS1saW5rLW9ubHktMzItYnl0ZXMtb2YtZW50cm9weQ";
        user.setForcePasswordChange(true);
        user.setTemporaryPasswordExpiresAt(NOW.plusHours(10));
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.plusHours(1))
            .build();
        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("newPassword")).thenReturn("encodedPwd");

        service.confirmReset(rawToken, "newPassword");

        assertThat(user.isForcePasswordChange()).isFalse();
        assertThat(user.getTemporaryPasswordExpiresAt()).isNull();
    }

    /** The stored form of a raw token: lower-case hex SHA-256, as the service writes it. */
    private static String hashOf(String raw) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void verifyToken_valid() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW.plusHours(1))
            .build();

        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));
        assertThat(service.verifyToken(rawToken)).isTrue();
    }

    @Test
    void verifyToken_notFound() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.empty());
        assertThat(service.verifyToken(rawToken)).isFalse();
    }

    @Test
    void cleanupExpiredTokens_returnsCount() {
        when(tokenRepository.deleteByExpirationBefore(NOW)).thenReturn(5L);
        int result = service.cleanupExpiredTokens();
        assertThat(result).isEqualTo(5);
    }

    @Test
    void verifyToken_expiryBoundaryUsesInjectedClock() {
        String rawToken = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        PasswordResetToken resetToken = PasswordResetToken.builder()
            .user(user)
            .tokenHash(hashOf(rawToken))
            .expiration(NOW)
            .build();
        when(tokenRepository.findByTokenHash(hashOf(rawToken))).thenReturn(Optional.of(resetToken));

        // isAfter is strict: still valid at exactly the expiration instant ...
        assertThat(service.verifyToken(rawToken)).isTrue();
        // ... and expired one nanosecond before "now".
        resetToken.setExpiration(NOW.minusNanos(1));
        assertThat(service.verifyToken(rawToken)).isFalse();
    }

    @Test
    void requestReset_stampsTwoHourExpiryFromInjectedClock() {
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestReset("test@test.com", Locale.ENGLISH);

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getExpiration()).isEqualTo(NOW.plusHours(2));
    }

    @Test
    void invalidateAllForUser_success() {
        service.invalidateAllForUser(userId);
        verify(tokenRepository).deleteByUser_IdAndConsumedAtIsNull(userId);
    }

    @Test
    void theRawTokenIsLoggedOnADeveloperMachineOnly() {
        assertThat(PasswordResetServiceImpl.shouldLogRawToken(new String[] {"local-h2"})).isTrue();
        assertThat(PasswordResetServiceImpl.shouldLogRawToken(new String[] {"local"})).isTrue();
        assertThat(PasswordResetServiceImpl.shouldLogRawToken(new String[] {"local-h2", "local"})).isTrue();
        assertThat(PasswordResetServiceImpl.shouldLogRawToken(new String[] {"dev"})).isFalse();
        assertThat(PasswordResetServiceImpl.shouldLogRawToken(new String[] {"local-h2", "dev"})).isFalse();
        assertThat(PasswordResetServiceImpl.shouldLogRawToken(new String[] {"local", "prod"})).isFalse();
        assertThat(PasswordResetServiceImpl.shouldLogRawToken(new String[] {"test"})).isFalse();
        assertThat(PasswordResetServiceImpl.shouldLogRawToken(new String[0])).isFalse();
        assertThat(PasswordResetServiceImpl.shouldLogRawToken(null)).isFalse();
    }
}
