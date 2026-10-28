package com.example.hms.mapper.pharmacy;

import com.example.hms.enums.RoutingType;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1000: the routing reasons a French or Spanish pharmacist read in English
 * (s3 back-order screenshot, e1 Spanish run), rendered through the real
 * bundles and the real mapper.
 */
class RoutingReasonTextTest {

    private final MessageSource previous =
            (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames(com.example.hms.config.MessageBundles.basenames());
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setAlwaysUseMessageFormat(true);
        MessageUtil.setMessageSource(messageSource);
    }

    @AfterEach
    void tearDown() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void theStoredKeysOfAPrintAndABackOrderReadInFrenchAndSpanish() {
        assertThat(RoutingReasonText.readable(RoutingReasonText.PRINT_KEY, Locale.FRENCH))
                .startsWith("Ordonnance imprimée");
        assertThat(RoutingReasonText.readable(RoutingReasonText.BACK_ORDER_KEY, Locale.FRENCH))
                .isEqualTo("Médicament en rupture de stock ; mis en commande");
        assertThat(RoutingReasonText.readable(RoutingReasonText.BACK_ORDER_KEY, Locale.forLanguageTag("es")))
                .startsWith("Medicamento agotado");
    }

    @Test
    void theEnglishSentencesOlderRowsCarryAreTranslatedToo() {
        assertThat(RoutingReasonText.readable(
                "Prescription printed for patient to fill at external pharmacy", Locale.FRENCH))
                .startsWith("Ordonnance imprimée");
        assertThat(RoutingReasonText.readable("Medication out of stock; placed on back order", Locale.FRENCH))
                .doesNotContain("out of stock");
    }

    @Test
    void anSmsDispatchReasonKeepsTheNamesAndTheNoteButNotTheEnglish() {
        String stored = "Dispatched by SMS to PARTNER_PHARMACY Pharmacie du Marché. Note: urgent, appeler avant"
                + ". Superseded: re-dispatched by SMS to Pharmacie Centrale"
                + ". Not delivered: the SMS to the pharmacy failed";
        String fr = RoutingReasonText.readable(stored, Locale.FRENCH);
        assertThat(fr)
                .contains("pharmacie partenaire Pharmacie du Marché")
                .contains("Note : urgent, appeler avant")
                .contains("renvoyée par SMS à Pharmacie Centrale")
                .contains("l'envoi du SMS")
                .doesNotContain("Dispatched", "Superseded", "Not delivered", "PARTNER_PHARMACY");
    }

    @Test
    void thePharmaciststOwnWordsAreShownAsTyped() {
        assertThat(RoutingReasonText.readable("Le patient préfère cette pharmacie", Locale.FRENCH))
                .isEqualTo("Le patient préfère cette pharmacie");
        assertThat(RoutingReasonText.readable(null, Locale.FRENCH)).isNull();
    }

    @Test
    void theMapperRendersTheReasonInTheRequestLocale() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .routingType(RoutingType.BACKORDER)
                .reason(RoutingReasonText.BACK_ORDER_KEY)
                .build();
        assertThat(new PrescriptionRoutingMapper().toResponseDTO(decision).getReason())
                .isEqualTo("Médicament en rupture de stock ; mis en commande");
    }
}
