package com.example.hms.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailServiceImpl")
class EmailServiceImplTest {

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private EmailServiceImpl emailService;

    private static final String FRONTEND_BASE_URL = "https://dev.e-keneya.com";

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

    /**
     * Runs the captured preparator against a real (unsent) MimeMessage so a
     * test can assert on the copy itself. The wording is the product here:
     * this mail told inactive accounts to sign in immediately, which is how a
     * delivered activation code got reported as a missing activation email.
     */
    private String renderedHtml() {
        ArgumentCaptor<MimeMessagePreparator> captor =
            ArgumentCaptor.forClass(MimeMessagePreparator.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        try {
            captor.getValue().prepare(message);
            return message.getSubject() + System.lineSeparator() + collectText(message.getContent());
        } catch (Exception e) {
            throw new IllegalStateException("Could not render the prepared message", e);
        }
    }

    /** Flattens whatever the helper built (String, or a multipart tree). */
    private static String collectText(Object content) throws Exception {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof jakarta.mail.Multipart multipart) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                sb.append(collectText(multipart.getBodyPart(i).getContent()));
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    // =========================================================================
    // deliversRealEmail — the delivery report's NOT_CONFIGURED vs FAILED split
    // =========================================================================

    @Nested
    @DisplayName("deliversRealEmail")
    class DeliversRealEmail {

        private void configure(String host, String user, String pass, String auth) {
            ReflectionTestUtils.setField(emailService, "configuredMailHost", host);
            ReflectionTestUtils.setField(emailService, "configuredMailUsername", user);
            ReflectionTestUtils.setField(emailService, "configuredMailPassword", pass);
            ReflectionTestUtils.setField(emailService, "smtpAuthProperty", auth);
        }

        @Test
        @DisplayName("mirrors StartupSubsystemLogger: host required; auth=false relay OK; auth needs BOTH creds")
        void mirrorsTheDeploymentsOwnSmtpReadinessRules() {
            configure("", "user@x", "secret", "true");
            org.assertj.core.api.Assertions.assertThat(emailService.deliversRealEmail())
                .as("no host, no transport").isFalse();

            configure("smtp.gmail.com", "", "", "true");
            org.assertj.core.api.Assertions.assertThat(emailService.deliversRealEmail())
                .as("dev-outage shape: MAIL_USER/MAIL_PASS unset").isFalse();

            configure("smtp.gmail.com", "noreply@e-keneya.com", "", "true");
            org.assertj.core.api.Assertions.assertThat(emailService.deliversRealEmail())
                .as("missing MAIL_PASS alone must still read NOT_CONFIGURED, not FAILED")
                .isFalse();

            configure("smtp.gmail.com", "noreply@e-keneya.com", "secret", "true");
            org.assertj.core.api.Assertions.assertThat(emailService.deliversRealEmail()).isTrue();

            configure("relay.internal", "", "", "false");
            org.assertj.core.api.Assertions.assertThat(emailService.deliversRealEmail())
                .as("an unauthenticated relay needs no credentials")
                .isTrue();
        }

        @Test
        @DisplayName("interface default assumes a real transport")
        void interfaceDefaultsTrue() {
            // The contract for implementations that never report transport
            // state: assume real, never NOT_CONFIGURED.
            EmailService defaults = mock(EmailService.class,
                withSettings().defaultAnswer(org.mockito.Answers.CALLS_REAL_METHODS));
            org.assertj.core.api.Assertions.assertThat(defaults.deliversRealEmail()).isTrue();
        }
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
                "Temp@1234", "Hospital Admin", "City General Hospital",
                "https://portal.example/onboarding/role-welcome?assignment=A-1");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("succeeds when hospitalName is null (global / super-admin role)")
        void sendsWithoutHospital() {
            stubMailSender();
            emailService.sendAdminWelcomeEmail(
                "admin@hospital.com", "Jane Doe", "janedoe",
                "Temp@1234", "Super Admin", null,
                "https://portal.example/onboarding/role-welcome?assignment=A-2");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("succeeds when displayName is null (falls back gracefully)")
        void sendsWithNullDisplayName() {
            stubMailSender();
            emailService.sendAdminWelcomeEmail(
                "admin@hospital.com", null, "janedoe",
                "Temp@1234", "Doctor", "City Hospital", null);
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
        }

        @Test
        @DisplayName("points at activation, never at an immediate sign-in the account would refuse")
        void leadsWithActivationNotLogin() {
            stubMailSender();
            String activationUrl = "https://dev.e-keneya.com/onboarding/role-welcome?assignment=A-77";
            emailService.sendAdminWelcomeEmail(
                "nurse@hospital.com", "Awa Traore", "atraore",
                "Temp@1234", "Nurse", "City General Hospital", activationUrl);

            String html = renderedHtml();
            assertThat(html).contains(activationUrl);
            assertThat(html).contains("Activate My Account");
            // The account is inactive at this moment: nothing may promise
            // sign-in as the next step, and the subject must not claim ready.
            assertThat(html).doesNotContain("You can sign in immediately");
            assertThat(html).doesNotContain("Sign In to Your Account");
            assertThat(html).doesNotContain("Your Account Is Ready");
        }

        @Test
        @DisplayName("still names the confirmation step when no activation URL is known")
        void namesTheStepWithoutAUrl() {
            stubMailSender();
            emailService.sendAdminWelcomeEmail(
                "nurse@hospital.com", "Awa Traore", "atraore",
                "Temp@1234", "Nurse", "City General Hospital", null);

            String html = renderedHtml();
            assertThat(html).contains("confirmation code");
            assertThat(html).doesNotContain("Sign In to Your Account");
            assertThat(html).doesNotContain("You can sign in immediately");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for null recipient")
        void rejectsNullRecipient() {
            assertThatThrownBy(() ->
                emailService.sendAdminWelcomeEmail(
                    null, "Jane Doe", "janedoe", "Temp@1234", "Admin", "Hospital", null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for malformed recipient email")
        void rejectsInvalidRecipient() {
            assertThatThrownBy(() ->
                emailService.sendAdminWelcomeEmail(
                    "not-an-email", "Jane Doe", "janedoe", "Temp@1234", "Admin", "Hospital", null))
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

