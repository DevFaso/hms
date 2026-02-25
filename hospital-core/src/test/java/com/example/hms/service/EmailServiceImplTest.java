package com.example.hms.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailServiceImpl")
class EmailServiceImplTest {

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private EmailServiceImpl emailService;

    private static final String FRONTEND_BASE_URL = "https://hms.dev.bitnesttechs.com";

    @BeforeEach
    void injectFrontendBaseUrl() {
        ReflectionTestUtils.setField(emailService, "frontendBaseUrl", FRONTEND_BASE_URL);
    }

    /**
     * EmailServiceImpl.sendWithAttachment calls mailSender.send(MimeMessagePreparator),
     * which is a void method — stub it to do nothing so tests don't hit a real mail server.
     */
    private void stubMailSender() {
        doNothing().when(mailSender).send(any(MimeMessagePreparator.class));
    }

    // =========================================================================
    // sendPasswordResetEmail
    // =========================================================================

    @Nested
    @DisplayName("sendPasswordResetEmail")
    class SendPasswordResetEmail {

        @Test
        @DisplayName("delegates to mailSender once for a valid recipient")
        void sendsEmail() {
            stubMailSender();
            emailService.sendPasswordResetEmail("user@example.com", "https://example.com/reset?token=abc");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("delegates to mailSender with a non-default frontendBaseUrl in the body")
        void sendsEmailWithCustomBaseUrl() {
            ReflectionTestUtils.setField(emailService, "frontendBaseUrl", "https://staging.hms.example.com");
            stubMailSender();
            emailService.sendPasswordResetEmail(
                "user@example.com",
                "https://staging.hms.example.com/reset-password?token=xyz");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("throws IllegalArgumentException for blank recipient")
        void rejectsBlankRecipient() {
            assertThatThrownBy(() ->
                emailService.sendPasswordResetEmail("", "https://example.com/reset?token=abc"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for invalid email format")
        void rejectsInvalidEmailFormat() {
            assertThatThrownBy(() ->
                emailService.sendPasswordResetEmail("not-an-email", "https://example.com/reset"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // sendPasswordResetConfirmationEmail
    // =========================================================================

    @Nested
    @DisplayName("sendPasswordResetConfirmationEmail")
    class SendPasswordResetConfirmationEmail {

        @Test
        @DisplayName("delegates to mailSender once for a named recipient")
        void sendsForNamedRecipient() {
            stubMailSender();
            emailService.sendPasswordResetConfirmationEmail("user@example.com", "John Doe");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("delegates to mailSender once when displayName is blank (falls back to 'there')")
        void sendsWithBlankDisplayName() {
            stubMailSender();
            emailService.sendPasswordResetConfirmationEmail("user@example.com", "");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("delegates to mailSender once when displayName is null (falls back to 'there')")
        void sendsWithNullDisplayName() {
            stubMailSender();
            emailService.sendPasswordResetConfirmationEmail("user@example.com", null);
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("uses configured frontendBaseUrl in sign-in link (not yourapp.com)")
        void usesConfiguredLoginUrl() {
            ReflectionTestUtils.setField(emailService, "frontendBaseUrl", "https://custom.hms.example.com");
            stubMailSender();
            emailService.sendPasswordResetConfirmationEmail("user@example.com", "Alice");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("throws IllegalArgumentException for malformed recipient email")
        void rejectsInvalidRecipient() {
            assertThatThrownBy(() ->
                emailService.sendPasswordResetConfirmationEmail("bad@@email", "John"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for null recipient")
        void rejectsNullRecipient() {
            assertThatThrownBy(() ->
                emailService.sendPasswordResetConfirmationEmail(null, "John"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // validateAddresses — edge cases (exercised through sendHtml)
    // =========================================================================

    @Nested
    @DisplayName("validateAddresses")
    class ValidateAddresses {

        @Test
        @DisplayName("throws when recipient list is null")
        void throwsOnNullRecipientList() {
            assertThatThrownBy(() ->
                emailService.sendHtml(null, List.of(), List.of(), "subj", "<p>b</p>"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws when recipient list is empty")
        void throwsOnEmptyRecipientList() {
            assertThatThrownBy(() ->
                emailService.sendHtml(List.of(), List.of(), List.of(), "subj", "<p>b</p>"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws when a null entry is present in the recipient list")
        void throwsOnNullEntryInRecipientList() {
            List<String> withNull = new ArrayList<>();
            withNull.add(null);
            assertThatThrownBy(() ->
                emailService.sendHtml(withNull, List.of(), List.of(), "subj", "<p>b</p>"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // sendAdminWelcomeEmail
    // =========================================================================

    @Nested
    @DisplayName("sendAdminWelcomeEmail")
    class SendAdminWelcomeEmail {

        @Test
        @DisplayName("delegates to mailSender once for a fully populated request")
        void sendsForFullRequest() {
            stubMailSender();
            emailService.sendAdminWelcomeEmail(
                "admin@hospital.com", "Jane Doe", "janedoe",
                "Temp@1234", "Hospital Admin", "City General Hospital");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("succeeds when hospitalName is null (global / super-admin role)")
        void sendsWithoutHospital() {
            stubMailSender();
            emailService.sendAdminWelcomeEmail(
                "admin@hospital.com", "Jane Doe", "janedoe",
                "Temp@1234", "Super Admin", null);
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("succeeds when displayName is null (falls back gracefully)")
        void sendsWithNullDisplayName() {
            stubMailSender();
            emailService.sendAdminWelcomeEmail(
                "admin@hospital.com", null, "janedoe",
                "Temp@1234", "Doctor", "City Hospital");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("throws IllegalArgumentException for null recipient")
        void rejectsNullRecipient() {
            assertThatThrownBy(() ->
                emailService.sendAdminWelcomeEmail(
                    null, "Jane Doe", "janedoe", "Temp@1234", "Admin", "Hospital"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for malformed recipient email")
        void rejectsInvalidRecipient() {
            assertThatThrownBy(() ->
                emailService.sendAdminWelcomeEmail(
                    "not-an-email", "Jane Doe", "janedoe", "Temp@1234", "Admin", "Hospital"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // sendAccountRestoredEmail
    // =========================================================================

    @Nested
    @DisplayName("sendAccountRestoredEmail")
    class SendAccountRestoredEmail {

        @Test
        @DisplayName("delegates to mailSender once for a valid named recipient")
        void sendsForNamedRecipient() {
            stubMailSender();
            emailService.sendAccountRestoredEmail("user@example.com", "John Doe");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("delegates to mailSender once when displayName is blank (falls back to 'there')")
        void sendsWithBlankDisplayName() {
            stubMailSender();
            emailService.sendAccountRestoredEmail("user@example.com", "");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("delegates to mailSender once when displayName is null (falls back to 'there')")
        void sendsWithNullDisplayName() {
            stubMailSender();
            emailService.sendAccountRestoredEmail("user@example.com", null);
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("throws IllegalArgumentException for null recipient")
        void rejectsNullRecipient() {
            assertThatThrownBy(() ->
                emailService.sendAccountRestoredEmail(null, "John Doe"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for malformed recipient email")
        void rejectsInvalidRecipient() {
            assertThatThrownBy(() ->
                emailService.sendAccountRestoredEmail("not-an-email", "John Doe"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

