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
            verify(smsService, never()).sendUsernameReminderSms(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("sends SMS when identifier is phone and smsService is available")
        void sendsSmsWhenIdentifierIsPhone() {
            String phoneIdentifier = "+1234567890";
            when(userRepository.findActiveByEmailOrPhone(phoneIdentifier)).thenReturn(Optional.of(user));

            service.sendReminder(phoneIdentifier, locale, requestIp);

            verify(smsService).sendUsernameReminderSms("+1234567890", "johndoe", locale);
            verify(emailService, never()).sendUsernameReminderEmail(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("falls back to email when identifier is phone but smsService is null")
        void fallsBackToEmailWhenSmsServiceNull() {
            // Construct service with null smsService manually
            UsernameReminderServiceImpl serviceWithoutSms = new UsernameReminderServiceImpl(
                    userRepository, emailService, null);

            String phoneIdentifier = "+1234567890";
            when(userRepository.findActiveByEmailOrPhone(phoneIdentifier)).thenReturn(Optional.of(user));

            serviceWithoutSms.sendReminder(phoneIdentifier, locale, requestIp);

            verify(emailService).sendUsernameReminderEmail("john@example.com", "johndoe", locale);
            verify(smsService, never()).sendUsernameReminderSms(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("does nothing when user not found (prevents account enumeration)")
        void doesNothingWhenUserNotFound() {
            when(userRepository.findActiveByEmailOrPhone("unknown@test.com")).thenReturn(Optional.empty());

            service.sendReminder("unknown@test.com", locale, requestIp);

            verify(emailService, never()).sendUsernameReminderEmail(anyString(), anyString(), any());
            verify(smsService, never()).sendUsernameReminderSms(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("does nothing when phone user not found")
        void doesNothingWhenPhoneUserNotFound() {
            when(userRepository.findActiveByEmailOrPhone("+9999999999")).thenReturn(Optional.empty());

            service.sendReminder("+9999999999", locale, requestIp);

            verify(emailService, never()).sendUsernameReminderEmail(anyString(), anyString(), any());
            verify(smsService, never()).sendUsernameReminderSms(anyString(), anyString(), any());
        }
    }
}
