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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules of {@link OwnEmailChangeService}, one at a time. The end-to-end
 * proof through the real filter chain is {@code OwnEmailChangeIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OwnEmailChangeServiceTest {

    private static final String PASSWORD_HASH = "$2a$10$ownHash";
    private static final String CODE_HASH = "$2a$10$codeHash";

    @Mock private UserRepository userRepository;
    @Mock private EmailChangeRequestRepository requestRepository;
    @Mock private EmailChangeWrites writes;
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
        verify(auditEventLogService, Mockito.atLeast(0)).logEvent(rows.capture());
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
            verify(writes, never()).applyEmail(any(), any());
            // Normalised as registration does, so the unique index backs the rule.
            assertThat(state.getPendingEmail()).isEqualTo("new@example.com");
            assertThat(state.getCodeHash()).isEqualTo(CODE_HASH);
            assertThat(state.getCodeSentAt()).isNotNull();
            assertThat(state.getCodeExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(14));
            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendEmailChangeVerificationEmail(eq("new@example.com"), code.capture(), any());
            assertThat(code.getValue()).matches("\\d{6}");
            verify(passwordEncoder).encode(code.getValue());
            verify(emailService, never()).sendEmailAddressInUseNoticeEmail(any(), any());
            assertThat(ActivationDeliveryTracker.close()).singleElement().satisfies(d -> {
                assertThat(d.getPurpose()).isEqualTo(NotificationDeliveryStatusDTO.PURPOSE_EMAIL_CHANGE_CODE);
                assertThat(d.getOutcome()).isEqualTo(NotificationDeliveryStatusDTO.OUTCOME_SENT);
                assertThat(d.getTarget()).isEqualTo("n***@example.com");
            });
            assertThat(auditRows()).singleElement().satisfies(r -> {
                assertThat(r.getStatus()).isEqualTo(AuditStatus.SUCCESS);
                assertThat(r.getEventDescription()).doesNotContain("new@").doesNotContain("old@");
            });
        }

        @Test
        @DisplayName("an address that already has an account: answered like a free one, the holder is notified instead of sent a code, and the attempt is audited")
        void takenAddressIsNotRevealed() {
            when(userRepository.existsEmailOnOtherAccount("superadmin@example.com", userId)).thenReturn(true);

            assertThatCode(() -> service.requestChange(userId, "Right-Pass-1", "SuperAdmin@Example.com"))
                .doesNotThrowAnyException();

            verify(emailService).sendEmailAddressInUseNoticeEmail(eq("superadmin@example.com"), any());
            verify(emailService, never()).sendEmailChangeVerificationEmail(any(), any(), any());
            // The same report a free address gets: one purpose, SENT, masked.
            assertThat(ActivationDeliveryTracker.close()).singleElement().satisfies(d -> {
                assertThat(d.getPurpose()).isEqualTo(NotificationDeliveryStatusDTO.PURPOSE_EMAIL_CHANGE_CODE);
                assertThat(d.getOutcome()).isEqualTo(NotificationDeliveryStatusDTO.OUTCOME_SENT);
                assertThat(d.getTarget()).isEqualTo("s***@example.com");
            });
            // A pending change exists, but its code was never sent to anyone.
            assertThat(state.getPendingEmail()).isEqualTo("superadmin@example.com");
            ArgumentCaptor<String> hashed = ArgumentCaptor.forClass(String.class);
            verify(passwordEncoder).encode(hashed.capture());
            assertThat(hashed.getValue()).doesNotMatch("\\d{6}");
            assertOneFailureRow("superadmin", "SuperAdmin");
        }

        @Test
        @DisplayName("no transport: the report says NOT_CONFIGURED instead of looking sent")
        void reportsAMissingTransport() {
            doThrow(new IllegalStateException("no host")).when(emailService)
                .sendEmailChangeVerificationEmail(any(), any(), any());
            when(emailService.deliversRealEmail()).thenReturn(false);

            service.requestChange(userId, "Right-Pass-1", "new@example.com");

            assertThat(ActivationDeliveryTracker.close()).singleElement().satisfies(d ->
                assertThat(d.getOutcome()).isEqualTo(NotificationDeliveryStatusDTO.OUTCOME_NOT_CONFIGURED));
        }

        @Test
        @DisplayName("a wrong password: refused, counted on THIS endpoint's counter, one FAILURE row")
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
        @DisplayName("the first request's row: a concurrent insert losing on the unique index reads the winner's row, and the wrong password still counts")
        void firstRequestRaceStillCounts() {
            when(requestRepository.findByUserId(userId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(state));
            doThrow(new DataIntegrityViolationException("uq_email_change_user"))
                .when(writes).createRow(userId);

            assertThatThrownBy(() -> service.requestChange(userId, "guess", "new@example.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MessageUtil.resolve("user.email.change.password"));

            assertThat(state.getPasswordFailures()).isEqualTo(1);
            verify(requestRepository).save(state);
        }

        @Test
        @DisplayName("the fifth wrong password in the window locks this endpoint")
        void fiveWrongPasswordsLockTheEndpoint() {
            for (int i = 0; i < OwnEmailChangeService.MAX_PASSWORD_FAILURES; i++) {
                assertThatThrownBy(() -> service.requestChange(userId, "guess", "new@example.com"))
                    .isInstanceOf(BusinessException.class);
            }
            assertThat(state.getPasswordLockedUntil()).isAfter(LocalDateTime.now().plusMinutes(14));

            Mockito.clearInvocations(passwordEncoder, auditEventLogService);
            assertThatThrownBy(() -> service.requestChange(userId, "Right-Pass-1", "new@example.com"))
                .hasMessage(MessageUtil.resolve("user.email.change.locked"));
            verify(passwordEncoder, never()).matches(any(), any());
            assertOneFailureRow("new@example.com");
        }

        @Test
        @DisplayName("the right password: the sixth request in the hour is refused, and nothing is mailed")
        void perUserRequestLimit() {
            for (int i = 0; i < OwnEmailChangeService.MAX_REQUESTS_PER_USER; i++) {
                service.requestChange(userId, "Right-Pass-1", "new" + i + "@example.com");
            }
            Mockito.clearInvocations(emailService, auditEventLogService);

            assertThatThrownBy(() -> service.requestChange(userId, "Right-Pass-1", "another@example.com"))
                .hasMessage(MessageUtil.resolve("user.email.change.ratelimited"));
            verify(emailService, never()).sendEmailChangeVerificationEmail(any(), any(), any());
            assertOneFailureRow("another@");
        }

        @Test
        @DisplayName("an address other accounts asked for three times this hour is refused, and nothing is mailed")
        void perAddressRequestLimit() {
            when(requestRepository.countOtherRequestsForAddressSince(eq("victim@example.com"), eq(userId), any()))
                .thenReturn((long) OwnEmailChangeService.MAX_OTHER_REQUESTS_PER_ADDRESS);

            assertThatThrownBy(() -> service.requestChange(userId, "Right-Pass-1", "victim@example.com"))
                .hasMessage(MessageUtil.resolve("user.email.change.ratelimited"));
            verify(emailService, never()).sendEmailChangeVerificationEmail(any(), any(), any());
            verify(emailService, never()).sendEmailAddressInUseNoticeEmail(any(), any());
            assertOneFailureRow("victim");
        }

        @Test
        @DisplayName("an address the mail sender would refuse is refused here, by the same rule")
        void undeliverableAddressIsRefused() {
            // One-letter TLD: a loose "x@y.z" shape accepts it, the sender does not.
            assertThatThrownBy(() -> service.requestChange(userId, "Right-Pass-1", "someone@example.c"))
                .hasMessage(MessageUtil.resolve("user.update.email.invalid"));
            assertOneFailureRow("someone");
        }

        @Test
        @DisplayName("the current address is refused as no change, audited")
        void sameAddressIsRefused() {
            assertThatThrownBy(() -> service.requestChange(userId, "Right-Pass-1", "OLD@example.com"))
                .hasMessage(MessageUtil.resolve("user.email.change.same"));
            assertOneFailureRow("old@example.com", "OLD@");
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
        @DisplayName("the right code applies the change in its own transaction and tells the OLD address, masked")
        void rightCodeAppliesAndNotifies() {
            service.confirmChange(userId, " 482913 ");

            verify(writes).applyEmail(userId, "new@example.com");
            assertThat(state.getPendingEmail()).isNull();
            verify(emailService).sendEmailChangedNoticeEmail(eq("old@example.com"), eq("Awa Traore"),
                eq("n***@example.com"), any());
            assertThat(ActivationDeliveryTracker.close()).singleElement().satisfies(d -> {
                assertThat(d.getPurpose()).isEqualTo(NotificationDeliveryStatusDTO.PURPOSE_EMAIL_CHANGE_NOTICE);
                assertThat(d.getTarget()).isEqualTo("o***@example.com");
            });
        }

        @Test
        @DisplayName("a unique violation on the write (a race) is the audited 400 'taken', not a 500")
        void writeRaceIsTaken() {
            doThrow(new DataIntegrityViolationException("uq_user_email")).when(writes)
                .applyEmail(userId, "new@example.com");

            assertThatThrownBy(() -> service.confirmChange(userId, "482913"))
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage(MessageUtil.resolve("user.update.email.taken"));
            verify(emailService, never()).sendEmailChangedNoticeEmail(any(), any(), any(), any());
            assertThat(auditRows()).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(AuditStatus.FAILURE));
        }

        @Test
        @DisplayName("an address another account took since the request: refused before the write")
        void addressTakenMeanwhile() {
            when(userRepository.existsEmailOnOtherAccount("new@example.com", userId)).thenReturn(true);

            assertThatThrownBy(() -> service.confirmChange(userId, "482913"))
                .hasMessage(MessageUtil.resolve("user.update.email.taken"));
            verify(writes, never()).applyEmail(any(), any());
        }

        @Test
        @DisplayName("an account with no previous address gets no notice")
        void noPreviousAddressNoNotice() {
            user.setEmail(null);

            service.confirmChange(userId, "482913");

            verify(emailService, never()).sendEmailChangedNoticeEmail(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a wrong code: refused (400), counted, one FAILURE row, nothing written")
        void wrongCodeIsCounted() {
            assertThatThrownBy(() -> service.confirmChange(userId, "000000"))
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage(MessageUtil.resolve("user.email.change.code.invalid"));

            assertThat(state.getCodeAttempts()).isEqualTo(1);
            assertThat(state.getPendingEmail()).isEqualTo("new@example.com");
            verify(writes, never()).applyEmail(any(), any());
            assertOneFailureRow("000000", "new@example.com");
        }

        @Test
        @DisplayName("the fifth wrong code cancels the change: gone (410)")
        void fifthWrongCodeCancels() {
            state.setCodeAttempts(OwnEmailChangeService.MAX_CODE_ATTEMPTS - 1);

            assertThatThrownBy(() -> service.confirmChange(userId, "000000"))
                .isInstanceOf(OwnEmailChangeService.PendingChangeGoneException.class)
                .hasMessage(MessageUtil.resolve("user.email.change.code.exhausted"));

            assertThat(state.getPendingEmail()).isNull();
            assertThatThrownBy(() -> service.confirmChange(userId, "482913"))
                .isInstanceOf(OwnEmailChangeService.PendingChangeGoneException.class);
            verify(writes, never()).applyEmail(any(), any());
        }

        @Test
        @DisplayName("an expired code: gone (410) and cleared")
        void expiredCodeIsGone() {
            state.setCodeExpiresAt(LocalDateTime.now().minusMinutes(1));

            assertThatThrownBy(() -> service.confirmChange(userId, "482913"))
                .isInstanceOf(OwnEmailChangeService.PendingChangeGoneException.class)
                .hasMessage(MessageUtil.resolve("user.email.change.expired"));
            assertThat(state.getPendingEmail()).isNull();
            assertOneFailureRow("new@example.com");
        }

        @Test
        @DisplayName("nothing pending: gone (410) and audited")
        void nothingPending() {
            when(requestRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmChange(userId, "482913"))
                .isInstanceOf(OwnEmailChangeService.PendingChangeGoneException.class)
                .hasMessage(MessageUtil.resolve("user.email.change.nopending"));
            assertOneFailureRow();
        }
    }
}
