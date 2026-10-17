package com.example.hms.service;

import com.example.hms.config.LocaleConfig;
import com.example.hms.service.mail.MailOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The onboarding mails rendered through the REAL bundles (D10–D12, D14):
 * e-Keneya branding, the role in the mail's language, and no unsubscribe
 * link on a mandatory account mail.
 */
@DisplayName("Onboarding e-mails rendered with the real bundles")
class OnboardingEmailRenderingTest {

    private final MessageSource messages = new LocaleConfig().messageSource();
    private MailOutboxService outbox;
    private EmailServiceImpl service;

    @BeforeEach
    void setUp() {
        outbox = mock(MailOutboxService.class);
        when(outbox.enqueue(any(), any(), any(), anyString(), anyString())).thenReturn(UUID.randomUUID());
        service = new EmailServiceImpl(mock(JavaMailSender.class), messages, outbox);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://dev.e-keneya.com");
        ReflectionTestUtils.setField(service, "configuredMailHost", "smtp.example.test");
        ReflectionTestUtils.setField(service, "configuredMailUsername", "noreply@example.test");
        ReflectionTestUtils.setField(service, "configuredMailPassword", "secret");
        ReflectionTestUtils.setField(service, "smtpAuthProperty", "true");
    }

    private String[] captured() {
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(1)).enqueue(any(), any(), any(), subject.capture(), body.capture());
        return new String[] {subject.getValue(), body.getValue()};
    }

    @Test
    @DisplayName("new staff welcome: one French mail, e-Keneya, Médecin, the code, no unsubscribe")
    void welcomeIsBrandedTranslatedAndSelfContained() {
        service.sendAdminWelcomeEmail("doc@hospital.test", "Awa Traore", "atraore", "Temp@1234",
            "ROLE_DOCTOR", "CHU Test",
            "https://dev.e-keneya.com/onboarding/role-welcome?assignment=A-1", "482913");

        String[] mail = captured();
        assertThat(mail[0]).isEqualTo("Bienvenue sur e-Keneya — Activez votre compte");
        assertThat(mail[1])
            .contains("Médecin")
            .contains("482913")
            .contains("48 heures")
            .contains("onboarding/role-welcome?assignment=A-1")
            .contains("e-Keneya")
            .doesNotContain("ROLE_DOCTOR")
            .doesNotContain(">Doctor<")
            .doesNotContain("HMS")
            .doesNotContain("confirmez votre affectation")
            .doesNotContainIgnoringCase("unsubscribe")
            .doesNotContainIgnoringCase("désabonner")
            .doesNotContainIgnoringCase("désinscri");
    }

    @Test
    @DisplayName("new staff with two roles: ONE French mail listing each role with its own code and link")
    void multiRoleWelcomeListsEveryRole() {
        service.sendAdminWelcomeEmail("rec@hospital.test", "Awa Traore", "atraore", "Temp@1234", java.util.List.of(
            new EmailService.WelcomeRole("ROLE_NURSE", "CHU Test", "111111",
                "https://dev.e-keneya.com/onboarding/role-welcome?assignment=A-1"),
            new EmailService.WelcomeRole("ROLE_DOCTOR", "CHU Test", "222222",
                "https://dev.e-keneya.com/onboarding/role-welcome?assignment=A-2")));

        String[] mail = captured();
        assertThat(mail[0]).isEqualTo("Bienvenue sur e-Keneya — Activez votre compte");
        assertThat(mail[1])
            .contains("Infirmier(ère) à CHU Test")
            .contains("Médecin à CHU Test")
            .contains("111111")
            .contains("222222")
            .contains("onboarding/role-welcome?assignment=A-1")
            .contains("onboarding/role-welcome?assignment=A-2")
            .contains("Les rôles ci-dessous sont prêts à être confirmés")
            .contains("Confirmer ce rôle")
            .contains("atraore")
            .doesNotContain("ROLE_NURSE")
            .doesNotContain("ROLE_DOCTOR")
            .doesNotContain("message séparé");
        assertThat(mail[1].indexOf("111111")).isLessThan(mail[1].indexOf("assignment=A-1"));
        assertThat(mail[1].indexOf("assignment=A-1")).isLessThan(mail[1].indexOf("222222"));
    }

    @Test
    @DisplayName("additional assignment for an existing user: role label in French, never the raw code")
    void assignmentMailTranslatesTheRole() {
        service.sendRoleAssignmentConfirmationEmail("doc@hospital.test", "Awa Traore", "ROLE_NURSE",
            "CHU Test", "123456", "A-2", "https://dev.e-keneya.com/onboarding/role-welcome?assignment=A-2",
            null, null);

        String[] mail = captured();
        assertThat(mail[0]).doesNotContain("HMS");
        assertThat(mail[1])
            .contains("Infirmier(ère)")
            .doesNotContain("ROLE_NURSE")
            .doesNotContainIgnoringCase("unsubscribe");
    }

    @Test
    @DisplayName("role labels resolve in EN/FR/ES and an unknown role is humanised")
    void roleLabelsInEveryLanguage() {
        assertThat(service.roleLabel(Locale.FRENCH, "ROLE_DOCTOR")).isEqualTo("Médecin");
        assertThat(service.roleLabel(Locale.ENGLISH, "ROLE_DOCTOR")).isEqualTo("Doctor");
        assertThat(service.roleLabel(Locale.of("es"), "ROLE_DOCTOR")).isEqualTo("Médico(a)");
        assertThat(service.roleLabel(Locale.FRENCH, "Hospital Admin")).isEqualTo("Administrateur hôpital");
        assertThat(service.roleLabel(Locale.FRENCH, "ROLE_SOMETHING_NEW")).isEqualTo("Something New");
    }

    @Test
    @DisplayName("no user-visible e-mail string says HMS in any language")
    void noHmsInEmailStrings() {
        for (Locale l : new Locale[] {Locale.ROOT, Locale.ENGLISH, Locale.FRENCH, Locale.of("es")}) {
            for (String key : new String[] {"email.admin.welcome.subject", "email.admin.welcome.heading",
                "email.password.changed.subject", "email.account.restored.subject",
                "email.change.notice.subject", "email.common.footer.copyright", "email.common.brand"}) {
                assertThat(messages.getMessage(key, new Object[] {"2026"}, l)).doesNotContain("HMS");
            }
        }
    }
}
