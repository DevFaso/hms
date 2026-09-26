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
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Session;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailServiceImpl")
class EmailServiceImplTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private EmailServiceImpl emailService;

    private static final String FRONTEND_BASE_URL = "https://dev.e-keneya.com";

    @BeforeEach
    void injectFrontendBaseUrl() {
        ReflectionTestUtils.setField(emailService, "frontendBaseUrl", FRONTEND_BASE_URL);
        // Every sentence comes from the bundle. The mock renders a key as
        // "key" or "key[arg|arg]" so a test can assert both that the right key
        // was asked for and that the dynamic values reached it — the real
        // wording is the translators', not this test's.
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenAnswer(inv -> renderKey(inv.getArgument(0), inv.getArgument(1)));
    }

    private static String renderKey(String key, Object[] args) {
        if (args == null || args.length == 0) {
            return key;
        }
        return key + "[" + Arrays.stream(args).map(String::valueOf).collect(Collectors.joining("|")) + "]";
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
    // Appointment mails — rendered in the PATIENT's language
    // =========================================================================

    @Nested
    @DisplayName("appointment mails")
    class AppointmentMails {

        private static final String RESCHEDULE = "https://dev.e-keneya.com/appointments/reschedule/A-1";
        private static final String CANCEL = "https://dev.e-keneya.com/appointments/cancel/A-1";

        @Test
        @DisplayName("confirmation resolves every sentence in the locale the caller passed")
        void confirmationUsesTheRecipientLocale() {
            stubMailSender();
            emailService.sendAppointmentConfirmationEmail(
                "awa@example.com", "Awa Traore", "CHU Yalgado", "Ouedraogo",
                "2026-09-05", "09:00 - 09:30", "contact@chu.bf", "+226 25 00 00 00",
                RESCHEDULE, CANCEL, Locale.ENGLISH);

            verify(messageSource).getMessage(eq("email.appointment.confirmed.subject"), any(), eq(Locale.ENGLISH));
            verify(messageSource, never()).getMessage(anyString(), any(), eq(Locale.FRENCH));

            String html = renderedHtml();
            assertThat(html)
                .startsWith("email.appointment.confirmed.subject")
                .contains("email.common.greeting.dear[Awa Traore]")
                .contains("email.appointment.confirmed.body.intro[CHU Yalgado|Ouedraogo]")
                .contains("email.appointment.label.date")
                .contains("2026-09-05")
                .contains("09:00 - 09:30")
                .contains("href=\"" + RESCHEDULE + "\"")
                .contains("href=\"" + CANCEL + "\"")
                .contains("email.appointment.contact[contact@chu.bf|+226 25 00 00 00]");
        }


        @Test
        @DisplayName("a null locale means the product default, not an NPE")
        void nullLocaleFallsBackToTheProductDefault() {
            stubMailSender();
            emailService.sendAppointmentNoShowEmail(
                "awa@example.com", "Awa Traore", "CHU Yalgado", "Ouedraogo",
                "2026-09-05", "09:00 - 09:30", "contact@chu.bf", "+226 25 00 00 00", null);

            verify(messageSource).getMessage(
                eq("email.appointment.noshow.subject"), any(), eq(EmailService.DEFAULT_RECIPIENT_LOCALE));
            assertThat(renderedHtml()).contains("email.appointment.noshow.contact[");
        }

        @Test
        @DisplayName("rescheduled mail names the NEW date and time and the reschedule-again prose")
        void rescheduledUsesTheNewLabels() {
            stubMailSender();
            emailService.sendAppointmentRescheduledEmail(
                "awa@example.com", "Awa Traore", "CHU Yalgado", "Ouedraogo",
                "2026-09-06", "10:00 - 10:30", "contact@chu.bf", "+226 25 00 00 00",
                RESCHEDULE, CANCEL, Locale.of("es"));

            String html = renderedHtml();
            assertThat(html)
                .contains("email.appointment.label.newDate")
                .contains("email.appointment.label.newTime")
                .contains("email.appointment.rescheduled.links.intro");
            verify(messageSource).getMessage(eq("email.appointment.rescheduled.subject"), any(), eq(Locale.of("es")));
        }

        @Test
        @DisplayName("user-supplied values are HTML-escaped before they reach the body")
        void escapesUserSuppliedValues() {
            stubMailSender();
            emailService.sendAppointmentCompletedEmail(
                "awa@example.com", "<script>alert(1)</script>", "CHU <b>Yalgado</b>", "O'Neil",
                "2026-09-05", "09:00 - 09:30", "contact@chu.bf", "+226 25 00 00 00", Locale.FRENCH);

            String html = renderedHtml();
            assertThat(html).doesNotContain("<script>");
            assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
            assertThat(html).contains("CHU &lt;b&gt;Yalgado&lt;/b&gt;");
            assertThat(html).contains("O&#x27;Neil");
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
        @DisplayName("renders in the product default locale and carries the reset link, the sign-in link and the 2-hour notice")
        void rendersFromTheBundle() {
            ReflectionTestUtils.setField(emailService, "frontendBaseUrl", "https://staging.hms.example.com");
            stubMailSender();
            String resetLink = "https://staging.hms.example.com/reset-password?token=xyz";
            emailService.sendPasswordResetEmail("user@example.com", resetLink);

            verify(messageSource).getMessage(eq("email.password.reset.subject"), any(), eq(Locale.FRENCH));
            String html = renderedHtml();
            assertThat(html)
                .startsWith("email.password.reset.subject")
                .contains("href=\"" + resetLink + "\"")
                .contains("email.password.reset.button");
            // The "did you ask for this?" sentence carries the sign-in anchor as its argument.
            assertThat(html)
                .contains("email.password.reset.body.unexpected.action[<a href=\"https://staging.hms.example.com/login\"")
                .contains("email.password.reset.body.unexpected.link")
                .contains("email.password.reset.body.expiry")
                .contains("email.password.reset.body.once")
                .contains("email.common.footer.copyright[" + java.time.Year.now().getValue() + "]");
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
        @DisplayName("delegates to mailSender once for a named recipient, greeting them by name")
        void sendsForNamedRecipient() {
            stubMailSender();
            emailService.sendPasswordResetConfirmationEmail("user@example.com", "John Doe");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
            String html = renderedHtml();
            assertThat(html)
                .contains("email.common.greeting.hi[John Doe]")
                .contains("email.password.changed.body.intro[")
                .contains("email.password.changed.body.unexpected[<strong><a href=\"" + FRONTEND_BASE_URL + "/login\"");
        }

        @Test
        @DisplayName("a staff mail renders in the product default: the recipient has no recorded language")
        void staffMailRendersInTheProductDefault() {
            stubMailSender();
            emailService.sendPasswordResetConfirmationEmail("user@example.com", "John Doe");
            verify(messageSource).getMessage(eq("email.password.changed.subject"), any(), eq(Locale.FRENCH));
            verify(messageSource, never()).getMessage(anyString(), any(), eq(Locale.ENGLISH));
        }

        @Test
        @DisplayName("falls back to the anonymous greeting when displayName is blank")
        void sendsWithBlankDisplayName() {
            stubMailSender();
            emailService.sendPasswordResetConfirmationEmail("user@example.com", "");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
            assertThat(renderedHtml()).contains("email.common.greeting.anonymous");
        }

        @Test
        @DisplayName("falls back to the anonymous greeting when displayName is null")
        void sendsWithNullDisplayName() {
            stubMailSender();
            emailService.sendPasswordResetConfirmationEmail("user@example.com", null);
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
            assertThat(renderedHtml()).contains("email.common.greeting.anonymous");
        }

        @Test
        @DisplayName("uses configured frontendBaseUrl in sign-in link (not yourapp.com)")
        void usesConfiguredLoginUrl() {
            ReflectionTestUtils.setField(emailService, "frontendBaseUrl", "https://custom.hms.example.com");
            stubMailSender();
            emailService.sendPasswordResetConfirmationEmail("user@example.com", "Alice");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
            String html = renderedHtml();
            assertThat(html)
                .contains("href=\"https://custom.hms.example.com/login\"")
                .doesNotContain("yourapp.com");
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
    // sendRoleAssignmentConfirmationEmail
    // =========================================================================

    @Nested
    @DisplayName("sendRoleAssignmentConfirmationEmail")
    class SendRoleAssignmentConfirmationEmail {

        @Test
        @DisplayName("a PATIENT role gets the welcome mail: subject names the hospital, body carries the code and the 48-hour notice")
        void patientRoleGetsTheWelcomeMail() {
            stubMailSender();
            emailService.sendRoleAssignmentConfirmationEmail(
                "awa@example.com", "Awa Traore", "ROLE_PATIENT", "CHU Yalgado",
                "123456", "AS-1", "https://dev.e-keneya.com/onboarding/role-welcome?assignment=AS-1",
                "atraore", "Temp@1234");

            String html = renderedHtml();
            assertThat(html)
                .startsWith("email.patient.welcome.subject[CHU Yalgado]")
                .contains("email.patient.welcome.heading[CHU Yalgado]")
                .contains("email.common.greeting.hi[Awa Traore]")
                .contains("123456")
                .contains("email.patient.welcome.button")
                .contains("email.patient.welcome.body.expiry")
                .contains("email.role.assignment.credentials.username")
                .contains("atraore")
                .doesNotContain("email.role.assignment.subject");
            verify(messageSource).getMessage(eq("email.patient.welcome.subject"), any(), eq(Locale.FRENCH));
        }

        @Test
        @DisplayName("a staff role gets the assignment mail with the assignment reference")
        void staffRoleGetsTheAssignmentMail() {
            stubMailSender();
            emailService.sendRoleAssignmentConfirmationEmail(
                "nurse@example.com", "Awa Traore", "Nurse", "CHU Yalgado",
                "654321", "AS-2", null, null, null);

            String html = renderedHtml();
            assertThat(html)
                .startsWith("email.role.assignment.subject")
                .contains("email.role.assignment.body.assigned[Nurse|CHU Yalgado]")
                .contains("email.role.assignment.body.reference[AS-2]")
                .contains("654321");
            // No URL, no button; no temp credentials, no credentials box.
            assertThat(html)
                .doesNotContain("email.role.assignment.button")
                .doesNotContain("email.role.assignment.credentials.title");
        }

        @Test
        @DisplayName("blank role and hospital fall back to bundle sentences, not English literals")
        void fallbacksComeFromTheBundle() {
            stubMailSender();
            emailService.sendRoleAssignmentConfirmationEmail(
                "nurse@example.com", null, "", null, "111111", "AS-3", null, null, null);

            String html = renderedHtml();
            assertThat(html)
                .contains("email.role.assignment.body.assigned[email.role.assignment.fallback.role|email.role.assignment.fallback.hospital]")
                .contains("email.common.greeting.anonymous")
                .doesNotContain("the assigned role")
                .doesNotContain("our hospital network");
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
        @DisplayName("succeeds when hospitalName is null (global / super-admin role) and drops the hospital row")
        void sendsWithoutHospital() {
            stubMailSender();
            emailService.sendAdminWelcomeEmail(
                "admin@hospital.com", "Jane Doe", "janedoe",
                "Temp@1234", "Super Admin", null,
                "https://portal.example/onboarding/role-welcome?assignment=A-2");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
            String html = renderedHtml();
            assertThat(html)
                .contains("email.admin.welcome.body.created[Super Admin]")
                .doesNotContain("email.admin.welcome.body.created.hospital")
                .doesNotContain("email.admin.welcome.credentials.hospital");
        }

        @Test
        @DisplayName("succeeds when displayName is null (falls back gracefully)")
        void sendsWithNullDisplayName() {
            stubMailSender();
            emailService.sendAdminWelcomeEmail(
                "admin@hospital.com", null, "janedoe",
                "Temp@1234", "Doctor", "City Hospital", null);
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
            assertThat(renderedHtml()).contains("email.common.greeting.anonymous");
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
            assertThat(html)
                .contains(activationUrl)
                .contains("email.activation.button");
            // The account is inactive at this moment: nothing may promise
            // sign-in as the next step, and the subject must not claim ready.
            assertThat(html)
                .doesNotContain("email.common.signin.button")
                .startsWith("email.admin.welcome.subject");
            // The step names the assignment mail by the SAME subject key, so
            // the two mails agree in whatever language they render in.
            assertThat(html)
                .contains("email.admin.welcome.body.step[email.role.assignment.subject]")
                .contains("email.admin.welcome.cta.signin.activated[<a href=\"" + FRONTEND_BASE_URL + "/login\"");
        }

        @Test
        @DisplayName("offers no button at all when no activation URL is known")
        void namesTheStepWithoutAUrl() {
            stubMailSender();
            emailService.sendAdminWelcomeEmail(
                "nurse@hospital.com", "Awa Traore", "atraore",
                "Temp@1234", "Nurse", "City General Hospital", null);

            String html = renderedHtml();
            assertThat(html)
                .contains("email.admin.welcome.cta.code")
                .doesNotContain("email.common.signin.button")
                .doesNotContain("email.activation.button");
            // This branch is what a two-role registration gets. A prominent
            // button is only ever offered for the activation screen: pointing
            // one at /login would lead straight to the rejection this mail
            // exists to prevent. The login address stays as secondary text.
            assertThat(html)
                .doesNotContain("display:inline-block")
                .contains("email.admin.welcome.cta.signin.after[<a href=\"" + FRONTEND_BASE_URL + "/login\"");
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
            String html = renderedHtml();
            assertThat(html)
                .startsWith("email.account.restored.subject")
                .contains("email.common.greeting.hi[John Doe]")
                .contains("email.account.restored.body.unexpected");
        }

        @Test
        @DisplayName("delegates to mailSender once when displayName is blank (anonymous greeting)")
        void sendsWithBlankDisplayName() {
            stubMailSender();
            emailService.sendAccountRestoredEmail("user@example.com", "");
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
            assertThat(renderedHtml()).contains("email.common.greeting.anonymous");
        }

        @Test
        @DisplayName("delegates to mailSender once when displayName is null (anonymous greeting)")
        void sendsWithNullDisplayName() {
            stubMailSender();
            emailService.sendAccountRestoredEmail("user@example.com", null);
            verify(mailSender, times(1)).send(any(MimeMessagePreparator.class));
            assertThat(renderedHtml()).contains("email.common.greeting.anonymous");
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

    // =========================================================================
    // sendRecoveryContactVerificationEmail
    // =========================================================================

    @Nested
    @DisplayName("sendRecoveryContactVerificationEmail")
    class SendRecoveryContactVerificationEmail {

        @Test
        @DisplayName("carries the code and the 15-minute expiry sentence, in the product default locale")
        void carriesTheCodeAndTheExpiry() {
            stubMailSender();
            emailService.sendRecoveryContactVerificationEmail("backup@example.com", "482913");

            String html = renderedHtml();
            assertThat(html)
                .startsWith("email.recovery.contact.subject")
                .contains("482913")
                .contains("email.recovery.contact.body.expiry")
                .contains("email.common.unexpected.title");
            verify(messageSource).getMessage(eq("email.recovery.contact.subject"), any(), eq(Locale.FRENCH));
        }

        @Test
        @DisplayName("throws IllegalArgumentException for null recipient")
        void rejectsNullRecipient() {
            assertThatThrownBy(() ->
                emailService.sendRecoveryContactVerificationEmail(null, "482913"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // sendUsernameReminderEmail — the one mail whose request locale IS the recipient's
    // =========================================================================

    @Nested
    @DisplayName("sendUsernameReminderEmail")
    class SendUsernameReminderEmail {

        @Test
        @DisplayName("renders in the locale the requester filled the form in")
        void usesTheGivenLocale() {
            stubMailSender();
            emailService.sendUsernameReminderEmail("user@example.com", "jdoe", Locale.of("es"));

            verify(messageSource).getMessage(eq("email.username.reminder.subject"), any(), eq(Locale.of("es")));
            String html = renderedHtml();
            assertThat(html)
                .contains("jdoe")
                .contains("email.username.reminder.body.signin[<a href=\"" + FRONTEND_BASE_URL + "/login\"");
        }

        @Test
        @DisplayName("a null locale renders in the product default")
        void nullLocaleFallsBack() {
            stubMailSender();
            emailService.sendUsernameReminderEmail("user@example.com", "jdoe", null);
            verify(messageSource).getMessage(eq("email.username.reminder.subject"), any(), eq(Locale.FRENCH));
        }
    }

    // =========================================================================
    // Password rotation — plural handled by key choice, never by an English "s"
    // =========================================================================

    @Nested
    @DisplayName("password rotation mails")
    class PasswordRotationMails {

        @Test
        @DisplayName("one day left picks the singular sentence")
        void reminderSingular() {
            stubMailSender();
            emailService.sendPasswordRotationReminderEmail("user@example.com", "Jane", 1, LocalDate.of(2026, 9, 6));
            String html = renderedHtml();
            assertThat(html)
                .contains("email.password.rotation.reminder.body.remaining.one")
                .doesNotContain("remaining.many");
        }

        @Test
        @DisplayName("several days left picks the plural sentence with the count as text")
        void reminderPlural() {
            stubMailSender();
            emailService.sendPasswordRotationReminderEmail("user@example.com", "Jane", 7, LocalDate.of(2026, 9, 12));
            String html = renderedHtml();
            assertThat(html)
                .contains("email.password.rotation.reminder.body.remaining.many[7]")
                .contains("email.password.rotation.reminder.body.action[<a href=\"" + FRONTEND_BASE_URL + "/login\"");
            verify(messageSource).getMessage(eq("email.password.rotation.reminder.subject"), any(), eq(Locale.FRENCH));
        }

        @Test
        @DisplayName("the enforcement mail states the deadline and how long it has been overdue")
        void forceChange() {
            stubMailSender();
            emailService.sendPasswordRotationForceChangeEmail("user@example.com", "Jane", LocalDate.of(2026, 9, 1), 3);
            String html = renderedHtml();
            assertThat(html)
                .startsWith("email.password.rotation.force.subject")
                .contains("email.password.rotation.force.body.overdue.many[")
                .contains("|3]")
                .contains("email.password.rotation.force.body.restricted");
        }

        @Test
        @DisplayName("one day overdue picks the singular sentence")
        void forceChangeSingular() {
            stubMailSender();
            emailService.sendPasswordRotationForceChangeEmail("user@example.com", "Jane", LocalDate.of(2026, 9, 1), 1);
            assertThat(renderedHtml()).contains("email.password.rotation.force.body.overdue.one[");
        }
    }

    // =========================================================================
    // sendActivationEmail
    // =========================================================================

    @Nested
    @DisplayName("sendActivationEmail")
    class SendActivationEmail {

        @Test
        @DisplayName("the short form carries the link twice (button and fallback) and the 24-hour notice")
        void shortForm() {
            stubMailSender();
            String link = "https://dev.e-keneya.com/verify?email=awa%40example.com&token=t-1";
            emailService.sendActivationEmail("awa@example.com", link);

            String html = renderedHtml();
            assertThat(html).startsWith("email.activation.subject");
            // Escaped in the attribute and in the visible fallback: valid HTML, same URL once decoded.
            assertThat(html).contains("href=\"https://dev.e-keneya.com/verify?email=awa%40example.com&amp;token=t-1\"");
            assertThat(html)
                .contains("email.activation.button")
                .contains("email.activation.body.expiry")
                .doesNotContain("email.common.greeting.hi")
                .doesNotContain("email.activation.credentials.title");
        }

        @Test
        @DisplayName("the long form greets by name and shows the username with the set-a-password hint")
        void longForm() {
            stubMailSender();
            emailService.sendActivationEmail("awa@example.com", "https://dev.e-keneya.com/verify?token=t-2",
                "Awa Traore", "atraore", "CHU Yalgado");

            String html = renderedHtml();
            assertThat(html)
                .contains("email.common.greeting.hi[Awa Traore]")
                .contains("email.activation.credentials.title")
                .contains("atraore")
                .contains("email.activation.credentials.password.hint")
                .contains("CHU Yalgado");
        }
    }
}
