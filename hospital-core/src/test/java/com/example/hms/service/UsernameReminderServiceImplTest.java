package com.example.hms.service;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class UsernameReminderServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private SmsService smsService;
    @Mock private org.springframework.context.MessageSource messageSource;

    @InjectMocks
    private UsernameReminderServiceImpl service;

    private final Locale locale = Locale.ENGLISH;
    private final String requestIp = "192.168.1.1";
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("johndoe");
        user.setEmail("john@example.com");
        user.setPhoneNumber("+1234567890");
    }

    // ── sendReminder ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sendReminder")
    class SendReminder {

        @Test
        @DisplayName("sends email when identifier contains @ and user found")
        void sendsEmailWhenIdentifierIsEmail() {
            String emailIdentifier = "john@example.com";
            when(userRepository.findActiveByEmailOrPhone(emailIdentifier)).thenReturn(Optional.of(user));

            service.sendReminder(emailIdentifier, locale, requestIp);

            verify(emailService).sendUsernameReminderEmail("john@example.com", "johndoe", locale);
            verify(smsService, never()).send(anyString(), anyString());
        }

        @Test
        @DisplayName("sends SMS when identifier is phone and smsService is available")
        void sendsSmsWhenIdentifierIsPhone() {
            String phoneIdentifier = "+1234567890";
            when(userRepository.findActiveByEmailOrPhone(phoneIdentifier)).thenReturn(Optional.of(user));
            when(smsService.deliversRealSms()).thenReturn(true);

            when(messageSource.getMessage(org.mockito.ArgumentMatchers.eq("sms.username.reminder"), any(),
                    anyString(), org.mockito.ArgumentMatchers.eq(locale)))
                .thenReturn("e-Keneya: your username is johndoe");

            service.sendReminder(phoneIdentifier, locale, requestIp);

            // QA D1123 sibling: the text comes from the reader's bundle, not an English literal.
            verify(smsService).send("+1234567890", "e-Keneya: your username is johndoe");
            verify(emailService, never()).sendUsernameReminderEmail(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("D2000 class: the logging mock is not a transport; falls back to email, never calls send")
        void fallsBackToEmailWhenOnlyTheMockTransportExists() {
            String phoneIdentifier = "+1234567890";
            when(userRepository.findActiveByEmailOrPhone(phoneIdentifier)).thenReturn(Optional.of(user));
            when(smsService.deliversRealSms()).thenReturn(false);

            service.sendReminder(phoneIdentifier, locale, requestIp);

            verify(emailService).sendUsernameReminderEmail("john@example.com", "johndoe", locale);
            verify(smsService, never()).send(anyString(), anyString());
        }

        @Test
        @DisplayName("D2000: a known phone whose email fallback fails answers exactly like an unknown identifier")
        void aFailedFallbackNeverTellsTheCallerTheAccountExists() {
            String phoneIdentifier = "+22670202020";
            when(userRepository.findActiveByEmailOrPhone(phoneIdentifier)).thenReturn(Optional.of(user));
            when(userRepository.findActiveByEmailOrPhone("+22670000001")).thenReturn(Optional.empty());
            when(smsService.deliversRealSms()).thenReturn(false);
            org.mockito.Mockito.doThrow(new com.example.hms.exception.NotificationTransportUnavailableException(
                    "Mail transport is not configured on this deployment"))
                .when(emailService).sendUsernameReminderEmail(anyString(), anyString(), any());

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.sendReminder(phoneIdentifier, locale, requestIp));
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.sendReminder("+22670000001", locale, requestIp));

            // No address on the account, a failing provider, a failing mail by email identifier: all silent too.
            user.setEmail(null);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.sendReminder(phoneIdentifier, locale, requestIp));
            when(smsService.deliversRealSms()).thenReturn(true);
            org.mockito.Mockito.doThrow(new IllegalStateException("sms.orange.authFailed"))
                .when(smsService).send(any(), any());
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.sendReminder(phoneIdentifier, locale, requestIp));
            user.setEmail("john@example.com");
            when(userRepository.findActiveByEmailOrPhone("john@example.com")).thenReturn(Optional.of(user));
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.sendReminder("john@example.com", locale, requestIp));
        }

        @Test
        @DisplayName("falls back to email when identifier is phone but smsService is null")
        void fallsBackToEmailWhenSmsServiceNull() {
            // Construct service with null smsService manually
            UsernameReminderServiceImpl serviceWithoutSms = new UsernameReminderServiceImpl(
                    userRepository, emailService, null, messageSource);

            String phoneIdentifier = "+1234567890";
            when(userRepository.findActiveByEmailOrPhone(phoneIdentifier)).thenReturn(Optional.of(user));

            serviceWithoutSms.sendReminder(phoneIdentifier, locale, requestIp);

            verify(emailService).sendUsernameReminderEmail("john@example.com", "johndoe", locale);
            verify(smsService, never()).send(anyString(), anyString());
        }

        @Test
        @DisplayName("does nothing when user not found (prevents account enumeration)")
        void doesNothingWhenUserNotFound() {
            when(userRepository.findActiveByEmailOrPhone("unknown@test.com")).thenReturn(Optional.empty());

            service.sendReminder("unknown@test.com", locale, requestIp);

            verify(emailService, never()).sendUsernameReminderEmail(anyString(), anyString(), any());
            verify(smsService, never()).send(anyString(), anyString());
        }

        @Test
        @DisplayName("does nothing when phone user not found")
        void doesNothingWhenPhoneUserNotFound() {
            when(userRepository.findActiveByEmailOrPhone("+9999999999")).thenReturn(Optional.empty());

            service.sendReminder("+9999999999", locale, requestIp);

            verify(emailService, never()).sendUsernameReminderEmail(anyString(), anyString(), any());
            verify(smsService, never()).send(anyString(), anyString());
        }
    
        @Test
        @DisplayName("D4271: an account that was never activated gets no reminder (its contact is not confirmed)")
        void anInactiveAccountGetsNothing() {
            user.setActive(false);
            when(userRepository.findActiveByEmailOrPhone("john@example.com")).thenReturn(Optional.of(user));

            service.sendReminder("john@example.com", locale, requestIp);

            verify(emailService, never()).sendUsernameReminderEmail(anyString(), anyString(), any());
            verify(smsService, never()).send(anyString(), anyString());
        }

        @Test
        @DisplayName("D4271: one delivered reminder per account in the cooldown, whoever asks and however often")
        void oneReminderPerCooldown() {
            when(userRepository.findActiveByEmailOrPhone("john@example.com")).thenReturn(Optional.of(user));

            service.sendReminder("john@example.com", locale, requestIp);
            service.sendReminder("john@example.com", locale, "203.0.113.9");
            service.sendReminder("john@example.com", locale, requestIp);

            verify(emailService, org.mockito.Mockito.times(1))
                .sendUsernameReminderEmail("john@example.com", "johndoe", locale);
        }

        @Test
        @DisplayName("D4271: a phone number typed the local way finds the account that stores it internationally")
        void aLocalPhoneNumberFindsTheAccount() {
            user.setPhoneNumber("+22670202020");
            when(userRepository.findActiveByEmailOrPhone("70 20 20 20")).thenReturn(Optional.empty());
            when(userRepository.findActiveByEmailOrPhone("+22670202020")).thenReturn(Optional.of(user));
            when(smsService.deliversRealSms()).thenReturn(false);

            service.sendReminder("70 20 20 20", locale, requestIp);

            verify(emailService).sendUsernameReminderEmail("john@example.com", "johndoe", locale);
        }

        @Test
        @DisplayName("D4271: two accounts under one identifier: nothing is sent; the failure goes to the caller, which logs it after answering")
        void anAmbiguousIdentifierSendsNothing() {
            when(userRepository.findActiveByEmailOrPhone("john@example.com"))
                .thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1, 2));

            org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.IncorrectResultSizeDataAccessException.class,
                () -> service.sendReminder("john@example.com", locale, requestIp));

            verify(emailService, never()).sendUsernameReminderEmail(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("ReminderCooldown")
    class Cooldown {

        private static final long T0 = 1_000_000_000L;

        @Test
        @DisplayName("one reminder per account per window, whoever asks")
        void onePerWindow() {
            var cooldown = new UsernameReminderServiceImpl.ReminderCooldown();
            UUID account = UUID.randomUUID();

            org.assertj.core.api.Assertions.assertThat(cooldown.recentlySent(account, T0)).isFalse();
            cooldown.sent(account, T0);

            org.assertj.core.api.Assertions.assertThat(cooldown.recentlySent(account, T0 + 1)).isTrue();
            org.assertj.core.api.Assertions.assertThat(
                cooldown.recentlySent(account, T0 + UsernameReminderServiceImpl.ReminderCooldown.WINDOW_MS)).isFalse();
            org.assertj.core.api.Assertions.assertThat(cooldown.recentlySent(UUID.randomUUID())).isFalse();
        }

        @Test
        @DisplayName("past its size the memory drops the accounts whose window has ended")
        void theMemoryIsBounded() {
            var cooldown = new UsernameReminderServiceImpl.ReminderCooldown();
            UUID recent = UUID.randomUUID();
            cooldown.sent(recent, T0 + UsernameReminderServiceImpl.ReminderCooldown.WINDOW_MS);
            for (int i = 0; i < UsernameReminderServiceImpl.ReminderCooldown.MAX_TRACKED; i++) {
                cooldown.sent(UUID.randomUUID(), T0);
            }

            long later = T0 + UsernameReminderServiceImpl.ReminderCooldown.WINDOW_MS + 1;
            cooldown.sent(UUID.randomUUID(), later);

            org.assertj.core.api.Assertions.assertThat(cooldown.tracked()).isEqualTo(2);
            org.assertj.core.api.Assertions.assertThat(cooldown.recentlySent(recent, later)).isTrue();
        }
    }
}
