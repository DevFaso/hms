package com.example.hms.service;

import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.EmailChangeRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.NotificationDeliveryStatusDTO;
import com.example.hms.repository.EmailChangeRequestRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.ActivationDeliveryTracker;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules of {@link OwnEmailChangeService}, one at a time. The end-to-end
 * proof through the real filter chain is {@code UserEndpointAuthorizationIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OwnEmailChangeServiceTest {

    private static final String PASSWORD_HASH = "$2a$10$ownHash";
    private static final String CODE_HASH = "$2a$10$codeHash";

    @Mock private UserRepository userRepository;
    @Mock private EmailChangeRequestRepository requestRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private AuditEventLogService auditEventLogService;

    @InjectMocks private OwnEmailChangeService service;

    private final UUID userId = UUID.randomUUID();
    private User user;
    private EmailChangeRequest state;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(userId);
        user.setUsername("awa");
        user.setEmail("old@example.com");
        user.setFirstName("Awa");
        user.setLastName("Traore");
        user.setPasswordHash(PASSWORD_HASH);
        state = EmailChangeRequest.builder().userId(userId).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(requestRepository.findByUserId(userId)).thenReturn(Optional.of(state));
        when(passwordEncoder.matches("Right-Pass-1", PASSWORD_HASH)).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn(CODE_HASH);
        ActivationDeliveryTracker.open();
    }

    @AfterEach
    void tearDown() {
        ActivationDeliveryTracker.close();
    }

    private List<AuditEventRequestDTO> auditRows() {
        ArgumentCaptor<AuditEventRequestDTO> rows = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, org.mockito.Mockito.atLeast(0)).logEvent(rows.capture());
        return rows.getAllValues();
    }

    /** Exactly one FAILURE row, carrying the account id only and none of these strings. */
    private void assertOneFailureRow(String... absent) {
        List<AuditEventRequestDTO> rows = auditRows();
        assertThat(rows).hasSize(1);
        AuditEventRequestDTO row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo(AuditStatus.FAILURE);
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getResourceId()).isEqualTo(userId.toString());
        assertThat(row.getDetails()).isNull();
        assertThat(row.getUserName()).isNull();
        assertThat(row.getResourceName()).isNull();
        for (String s : absent) {
            assertThat(row.getEventDescription()).doesNotContain(s);
        }
    }

    @Nested
    @DisplayName("step 1: the request")
    class Request {

        @Test
        @DisplayName("the right password and a free address: a code is stored hashed and mailed to the NEW address; users.email is untouched")
        void storesAPendingChangeAndMailsTheCode() {
            service.requestChange(userId, "Right-Pass-1", "  New@Example.COM ");

            assertThat(user.getEmail()).isEqualTo("old@example.com");
            verify(userRepository, never()).save(any());
            // Normalised as registration does, so the unique index backs the rule.
            assertThat(state.getPendingEmail()).isEqualTo("new@example.com");
            assertThat(state.getCodeHash()).isEqualTo(CODE_HASH);
            assertThat(state.getCodeExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(14));
            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendEmailChangeVerificationEmail(eq("new@example.com"), code.capture(), any());
            assertThat(code.getValue()).matches("\\d{6}");
            verify(passwordEncoder).encode(code.getValue());
            assertThat(ActivationDeliveryTracker.close()).singleElement().satisfies(d -> {
                assertThat(d.getPurpose()).isEqualTo(NotificationDeliveryStatusDTO.PURPOSE_EMAIL_CHANGE_CODE);
                assertThat(d.getOutcome()).isEqualTo(NotificationDeliveryStatusDTO.OUTCOME_SENT);
                assertThat(d.getTarget()).isEqualTo("n***@example.com");
            });
            List<AuditEventRequestDTO> rows = auditRows();
            assertThat(rows).singleElement().satisfies(r -> {
                assertThat(r.getStatus()).isEqualTo(AuditStatus.SUCCESS);
                assertThat(r.getEventDescription()).doesNotContain("new@").doesNotContain("old@");
            });
        }

        @Test
        @DisplayName("no transport: the report says NOT_CONFIGURED instead of looking sent")
        void reportsAMissingTransport() {
            doThrow(new IllegalStateException("no host")).when(emailService)
                .sendEmailChangeVerificationEmail(any(), any(), any());
            when(emailService.deliversRealEmail()).thenReturn(false);

            service.requestChange(userId, "Right-Pass-1", "new@example.com");

            assertThat(ActivationDeliveryTracker.close()).singleElement()
                .satisfies(d -> assertThat(d.getOutcome()).isEqualTo(NotificationDeliveryStatusDTO.OUTCOME_NOT_CONFIGURED));
        }

        @Test
        @DisplayName("a wrong password: refused, counted on THIS endpoint's counter (never the login throttle), one FAILURE row")
        void wrongPasswordIsCountedHere() {
            assertThatThrownBy(() -> service.requestChange(userId, "guess", "thief@evil.test"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MessageUtil.resolve("user.email.change.password"));

            assertThat(state.getPasswordFailures()).isEqualTo(1);
            assertThat(state.getPendingEmail()).isNull();
            verify(emailService, never()).sendEmailChangeVerificationEmail(any(), any(), any());
            assertOneFailureRow("thief", "old@example.com", "guess");
        }

        @Test
        @DisplayName("the fifth wrong password in the window locks this endpoint for 15 minutes")
        void fiveWrongPasswordsLockTheEndpoint() {
            for (int i = 0; i < OwnEmailChangeService.MAX_PASSWORD_FAILURES; i++) {
                assertThatThrownBy(() -> service.requestChange(userId, "guess", "new@example.com"))
                    .isInstanceOf(BusinessException.class);
            }
            assertThat(state.getPasswordLockedUntil()).isAfter(LocalDateTime.now().plusMinutes(14));

            // Now even the right password is refused, before it is checked.
            org.mockito.Mockito.clearInvocations(passwordEncoder, auditEventLogService);
            assertThatThrownBy(() -> service.requestChange(userId, "Right-Pass-1", "new@example.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MessageUtil.resolve("user.email.change.locked"));
            verify(passwordEncoder, never()).matches(any(), any());
            assertOneFailureRow("new@example.com");
        }

        @Test
        @DisplayName("failures outside the window start a new count")
        void oldFailuresExpire() {
            state.setPasswordFailures(4);
            state.setPasswordWindowStartedAt(LocalDateTime.now().minusMinutes(20));

            assertThatThrownBy(() -> service.requestChange(userId, "guess", "new@example.com"))
                .isInstanceOf(BusinessException.class);

            assertThat(state.getPasswordFailures()).isEqualTo(1);
            assertThat(state.getPasswordLockedUntil()).isNull();
        }

        @Test
        @DisplayName("the right password clears the counter")
        void rightPasswordClearsTheCounter() {
            state.setPasswordFailures(3);
            state.setPasswordWindowStartedAt(LocalDateTime.now());

            service.requestChange(userId, "Right-Pass-1", "new@example.com");

            assertThat(state.getPasswordFailures()).isZero();
        }

        @Test
        @DisplayName("an invalid, unchanged or taken address: refused, each with one FAILURE row that names no address")
        void addressRefusalsAreAudited() {
            assertThatThrownBy(() -> service.requestChange(userId, "Right-Pass-1", "not-an-address"))
                .hasMessage(MessageUtil.resolve("user.update.email.invalid"));
            assertOneFailureRow("not-an-address");

            org.mockito.Mockito.clearInvocations(auditEventLogService);
            assertThatThrownBy(() -> service.requestChange(userId, "Right-Pass-1", "OLD@example.com"))
                .hasMessage(MessageUtil.resolve("user.email.change.same"));
            assertOneFailureRow("old@example.com", "OLD@");

            org.mockito.Mockito.clearInvocations(auditEventLogService);
            when(userRepository.existsEmailOnOtherAccount("superadmin@example.com", userId)).thenReturn(true);
            assertThatThrownBy(() -> service.requestChange(userId, "Right-Pass-1", "SuperAdmin@Example.com"))
                .hasMessage(MessageUtil.resolve("user.update.email.taken"));
            assertOneFailureRow("superadmin", "SuperAdmin");

            assertThat(state.getPendingEmail()).isNull();
            verify(emailService, never()).sendEmailChangeVerificationEmail(any(), any(), any());
        }

        @Test
        @DisplayName("the single sign-on refusal is audited too")
        void singleSignOnRefusalIsAudited() {
            BusinessException refusal = service.refuseSingleSignOn(userId);

            assertThat(refusal).hasMessage(MessageUtil.resolve("user.email.change.external"));
            assertOneFailureRow();
        }
    }

    @Nested
    @DisplayName("step 2: the confirm")
    class Confirm {

        @BeforeEach
        void pending() {
            state.setPendingEmail("new@example.com");
            state.setCodeHash(CODE_HASH);
            state.setCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
            when(passwordEncoder.matches("482913", CODE_HASH)).thenReturn(true);
        }

        @Test
        @DisplayName("the right code applies the change, clears it, and tells the OLD address with the new one masked")
        void rightCodeAppliesAndNotifies() {
            service.confirmChange(userId, " 482913 ");

            assertThat(user.getEmail()).isEqualTo("new@example.com");
            verify(userRepository).save(user);
            assertThat(state.getPendingEmail()).isNull();
            assertThat(state.getCodeHash()).isNull();
            verify(emailService).sendEmailChangedNoticeEmail(eq("old@example.com"), eq("Awa Traore"),
                eq("n***@example.com"), any());
            assertThat(ActivationDeliveryTracker.close()).singleElement().satisfies(d -> {
                assertThat(d.getPurpose()).isEqualTo(NotificationDeliveryStatusDTO.PURPOSE_EMAIL_CHANGE_NOTICE);
                assertThat(d.getTarget()).isEqualTo("o***@example.com");
            });
        }

        @Test
        @DisplayName("an account with no previous address gets no notice")
        void noPreviousAddressNoNotice() {
            user.setEmail(null);

            service.confirmChange(userId, "482913");

            assertThat(user.getEmail()).isEqualTo("new@example.com");
            verify(emailService, never()).sendEmailChangedNoticeEmail(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a wrong code: refused, counted, one FAILURE row, users.email untouched")
        void wrongCodeIsCounted() {
            assertThatThrownBy(() -> service.confirmChange(userId, "000000"))
                .hasMessage(MessageUtil.resolve("user.email.change.code.invalid"));

            assertThat(state.getCodeAttempts()).isEqualTo(1);
            assertThat(state.getPendingEmail()).isEqualTo("new@example.com");
            assertThat(user.getEmail()).isEqualTo("old@example.com");
            verify(userRepository, never()).save(any());
            assertOneFailureRow("000000", "new@example.com");
        }

        @Test
        @DisplayName("the fifth wrong code cancels the change")
        void fifthWrongCodeCancels() {
            state.setCodeAttempts(OwnEmailChangeService.MAX_CODE_ATTEMPTS - 1);

            assertThatThrownBy(() -> service.confirmChange(userId, "000000"))
                .hasMessage(MessageUtil.resolve("user.email.change.code.exhausted"));

            assertThat(state.getPendingEmail()).isNull();
            assertThat(state.getCodeHash()).isNull();
            // Even the right code is useless now.
            assertThatThrownBy(() -> service.confirmChange(userId, "482913"))
                .hasMessage(MessageUtil.resolve("user.email.change.nopending"));
            assertThat(user.getEmail()).isEqualTo("old@example.com");
        }

        @Test
        @DisplayName("an expired code: refused and cleared")
        void expiredCodeIsCleared() {
            state.setCodeExpiresAt(LocalDateTime.now().minusMinutes(1));

            assertThatThrownBy(() -> service.confirmChange(userId, "482913"))
                .hasMessage(MessageUtil.resolve("user.email.change.expired"));

            assertThat(state.getPendingEmail()).isNull();
            assertThat(user.getEmail()).isEqualTo("old@example.com");
            assertOneFailureRow("new@example.com");
        }

        @Test
        @DisplayName("nothing pending: refused and audited")
        void nothingPending() {
            when(requestRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmChange(userId, "482913"))
                .hasMessage(MessageUtil.resolve("user.email.change.nopending"));
            assertOneFailureRow();
        }

        @Test
        @DisplayName("an address another account took since the request: refused, the change dropped")
        void addressTakenMeanwhile() {
            when(userRepository.existsEmailOnOtherAccount("new@example.com", userId)).thenReturn(true);

            assertThatThrownBy(() -> service.confirmChange(userId, "482913"))
                .hasMessage(MessageUtil.resolve("user.update.email.taken"));

            assertThat(user.getEmail()).isEqualTo("old@example.com");
            assertThat(state.getPendingEmail()).isNull();
            verify(emailService, times(0)).sendEmailChangedNoticeEmail(any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("normalisation matches registration: trimmed, lower case; blank is null")
    void normalisation() {
        assertThat(OwnEmailChangeService.normalize("  A.B@Example.COM ")).isEqualTo("a.b@example.com");
        assertThat(OwnEmailChangeService.normalize("   ")).isNull();
        assertThat(OwnEmailChangeService.normalize(null)).isNull();
    }
}
