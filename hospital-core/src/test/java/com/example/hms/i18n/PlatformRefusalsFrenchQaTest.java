package com.example.hms.i18n;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.service.integration.impl.Dhis2ConfigServiceImpl;
import com.example.hms.service.reporting.ScheduledReportService;
import com.example.hms.service.webhook.WebhookUrlValidator;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA round 14: refusals a French admin read in English. Each is raised through
 * the real code path with the real bundles, in the request locale the global
 * handler would see.
 */
@DisplayName("QA round 14: platform refusals are read in French")
class PlatformRefusalsFrenchQaTest {

    @BeforeEach
    void french() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
    }

    @AfterEach
    void reset() {
        MessageUtil.setMessageSource(null);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("D873: an http:// webhook URL is refused in French, on the url field")
    void webhookUrlRefusalIsFrench() {
        assertThatThrownBy(() -> WebhookUrlValidator.requireDeliverable("http://example.com/hook"))
            .isInstanceOf(FieldBusinessException.class)
            .hasMessage("Une URL de webhook doit commencer par https://.")
            .hasFieldOrPropertyWithValue("field", "url");
        assertThatThrownBy(() -> WebhookUrlValidator.requireDeliverable("https://localhost/hook"))
            .hasMessage("Une URL de webhook doit désigner un serveur accessible sur Internet, "
                + "pas une adresse locale ou privée.");
    }

    @Test
    @DisplayName("D898: an internal DHIS2 base URL is refused in French, English and Spanish, on baseUrl")
    void dhis2BaseUrlRefusalIsTranslated() {
        assertThatThrownBy(() -> Dhis2ConfigServiceImpl.requireCallableBaseUrl("https://169.254.169.254/latest"))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "baseUrl")
            .hasMessage("L'URL de base DHIS2 doit désigner un serveur accessible sur Internet, "
                + "pas une adresse locale ou privée.");
        assertThatThrownBy(() -> Dhis2ConfigServiceImpl.requireCallableBaseUrl("http://93.184.216.34"))
            .hasMessage("L'URL de base DHIS2 doit commencer par https://.");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThatThrownBy(() -> Dhis2ConfigServiceImpl.requireCallableBaseUrl("https://localhost/dhis"))
            .hasMessage("The DHIS2 base URL must point to a server reachable on the Internet, "
                + "not to a local or private address.");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));
        assertThatThrownBy(() -> Dhis2ConfigServiceImpl.requireCallableBaseUrl("https://10.0.0.5/dhis"))
            .hasMessage("La URL base de DHIS2 debe apuntar a un servidor accesible en Internet, "
                + "no a una dirección local o privada.");
    }

    @Test
    @DisplayName("D855: a malformed report recipient is refused in French, naming the address")
    void reportRecipientRefusalIsFrench() {
        assertThatThrownBy(() -> ScheduledReportService.requireDeliverableRecipients("accueil.clinique@@example"))
            .isInstanceOf(FieldBusinessException.class)
            .hasMessage("« accueil.clinique@@example » n'est pas une adresse e-mail valide.");
    }

    @Test
    @DisplayName("D854: the three slot-admin refusals of the report read in French")
    void slotAdminRefusalsAreFrench() {
        assertThat(new FieldBusinessException("code", "visitType.code.duplicate", "CPN-R13").getMessage())
            .isEqualTo("Un type de visite avec le code « CPN-R13 » existe déjà.");
        assertThat(new FieldBusinessException("endTime", "sessionTemplate.window.inverted").getMessage())
            .isEqualTo("Une séance doit se terminer après son début.");
        assertThat(new FieldBusinessException("to", "slot.generation.windowInverted").getMessage())
            .isEqualTo("La période de génération se termine avant de commencer.");
    }
}
