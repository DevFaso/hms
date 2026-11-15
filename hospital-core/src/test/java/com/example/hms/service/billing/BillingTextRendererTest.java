package com.example.hms.service.billing;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1901: billing text the server writes is shown in each reader's language
 * from its code; what a person typed is shown as typed, in every language.
 */
@DisplayName("D1901: BillingTextRenderer")
class BillingTextRendererTest {

    private static final Locale ES = Locale.forLanguageTag("es");

    @BeforeEach
    void bundles() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static String render(BillingText text, Locale locale) {
        return BillingTextRenderer.render(locale, text.code(), text.argsJson(), "stored");
    }

    @ParameterizedTest(name = "{0} in {1}")
    @CsvSource(delimiter = '|', value = {
        "billing.checkout.consultation|fr|Consultation",
        "billing.checkout.consultation|en|Consultation",
        "billing.checkout.consultation|es|Consulta",
        "billing.checkout.bedDays|fr|Hospitalisation (journées)",
        "billing.checkout.bedDays|en|Hospital stay (days)",
        "billing.checkout.bedDays|es|Hospitalización (días)",
        "maternity.delivery.charge.caesarean|fr|Accouchement (césarienne)",
        "maternity.delivery.charge.caesarean|en|Delivery (caesarean section)",
        "maternity.delivery.charge.caesarean|es|Parto (cesárea)",
        "maternity.delivery.charge.vaginal|fr|Accouchement (voie basse)",
        "maternity.delivery.charge.vaginal|en|Delivery (vaginal birth)",
        "maternity.delivery.charge.vaginal|es|Parto (vía vaginal)"
    })
    @DisplayName("a code without arguments, in each language")
    void aPlainCodeInEachLanguage(String code, String lang, String expected) {
        assertThat(render(BillingText.of(code), Locale.forLanguageTag(lang))).isEqualTo(expected);
    }

    @Test
    @DisplayName("the checkout note keeps the visit code in every language")
    void theDraftNoteInEachLanguage() {
        BillingText note = BillingText.of("billing.checkout.draftNote", BillingText.value("ENC-42"));
        assertThat(render(note, Locale.FRENCH))
            .isEqualTo("Visite ENC-42 clôturée : brouillon à vérifier, tarifer et émettre par la caisse.");
        assertThat(render(note, Locale.ENGLISH))
            .isEqualTo("Visit ENC-42 checked out: draft for the cashier to check, price and issue.");
        assertThat(render(note, ES))
            .isEqualTo("Visita ENC-42 cerrada: borrador para que la caja lo revise, tarife y emita.");
        assertThat(note.french())
            .isEqualTo("Visite ENC-42 clôturée : brouillon à vérifier, tarifer et émettre par la caisse.");
    }

    @Test
    @DisplayName("a transfusion names its product in the reader's language")
    void theTransfusionLineInEachLanguage() {
        BillingText line = BillingText.of("billing.checkout.transfusion",
            BillingText.code("transfusion.productType.PLATELETS"));
        assertThat(render(line, Locale.FRENCH)).isEqualTo("Transfusion : plaquettes");
        assertThat(render(line, Locale.ENGLISH)).isEqualTo("Transfusion: platelets");
        assertThat(render(line, ES)).isEqualTo("Transfusión: plaquetas");
    }

    @Test
    @DisplayName("the copay reason keeps the invoice number in every language")
    void theCopayReasonInEachLanguage() {
        BillingText reason = BillingText.of("billing.invoice.copayCreditedReason",
            BillingText.value("CHWF2396-2026-000004"));
        assertThat(render(reason, Locale.FRENCH))
            .isEqualTo("Ticket modérateur reporté sur la facture CHWF2396-2026-000004.");
        assertThat(render(reason, Locale.ENGLISH)).isEqualTo("Copay carried onto invoice CHWF2396-2026-000004.");
        assertThat(render(reason, ES)).isEqualTo("Copago trasladado a la factura CHWF2396-2026-000004.");
    }

    @Test
    @DisplayName("a date argument is written the reader's way, and the mode in their language")
    void theDeliveryNoteWithItsDate() {
        BillingText note = BillingText.of("maternity.delivery.charge.note",
            BillingText.code("maternity.delivery.charge.caesarean"), BillingText.date(LocalDate.of(2026, 11, 5)));
        assertThat(note.argsJson())
            .isEqualTo("[{\"code\":\"maternity.delivery.charge.caesarean\"},{\"date\":\"2026-11-05\"}]");
        assertThat(render(note, Locale.FRENCH)).isEqualTo("Frais de naissance : Accouchement (césarienne), né(e) le "
            + "5 nov. 2026. À tarifer si l'établissement n'a pas de prestation d'accouchement tarifée.");
        assertThat(render(note, Locale.ENGLISH)).isEqualTo("Birth charge: Delivery (caesarean section), born "
            + "Nov 5, 2026. Price it if the hospital has no priced delivery service.");
        assertThat(render(note, ES)).startsWith("Cargo de nacimiento: Parto (cesárea), nacido el 5 nov 2026.");
    }

    @Test
    @DisplayName("text a person typed has no code and is shown as typed in every language")
    void typedTextIsNeverTranslated() {
        for (Locale locale : new Locale[] {Locale.FRENCH, Locale.ENGLISH, ES}) {
            assertThat(BillingTextRenderer.render(locale, null, null, "Pansement simple")).isEqualTo("Pansement simple");
            assertThat(BillingTextRenderer.render(locale, " ", null, "Erreur de saisie")).isEqualTo("Erreur de saisie");
        }
    }

    @Test
    @DisplayName("an unknown code, an unknown argument code or unreadable arguments fall back to the stored text")
    void unknownCodesFallBackToTheStoredText() {
        assertThat(BillingTextRenderer.render(Locale.ENGLISH, "billing.no.such.key", null, "Texte gardé"))
            .isEqualTo("Texte gardé");
        assertThat(BillingTextRenderer.render(Locale.ENGLISH, "billing.checkout.transfusion",
            "[{\"code\":\"transfusion.productType.NOPE\"}]", "Transfusion : x")).isEqualTo("Transfusion : x");
        assertThat(BillingTextRenderer.render(Locale.ENGLISH, "billing.checkout.draftNote", "not json", "Visite V"))
            .isEqualTo("Visite V");
        assertThat(BillingTextRenderer.render(Locale.ENGLISH, "billing.checkout.draftNote",
            "[{\"colour\":\"x\"}]", "Visite V")).isEqualTo("Visite V");
        assertThat(BillingTextRenderer.render(Locale.ENGLISH, "maternity.delivery.charge.note",
            "[{\"code\":\"maternity.delivery.charge.vaginal\"},{\"date\":\"not-a-date\"}]", "Frais"))
            .isEqualTo("Frais");
    }

    @Test
    @DisplayName("the request's language is used when none is given")
    void theRequestLocale() {
        BillingText line = BillingText.of("billing.checkout.bedDays");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(BillingTextRenderer.render(line.code(), line.argsJson(), "Hospitalisation (journées)"))
            .isEqualTo("Hospital stay (days)");
    }

    @Test
    @DisplayName("an edit form's echo of the shown text keeps the code; anything else is the person's text")
    void anEchoIsNotAnEdit() {
        BillingText note = BillingText.of("billing.checkout.draftNote", BillingText.value("ENC-42"));
        String french = note.french();
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        String english = BillingTextRenderer.render(note.code(), note.argsJson(), french);
        assertThat(BillingTextRenderer.isShownText(english, note.code(), note.argsJson(), french)).isTrue();
        assertThat(BillingTextRenderer.isShownText(" " + french + " ", note.code(), note.argsJson(), french)).isTrue();
        assertThat(BillingTextRenderer.isShownText("Patient to call back", note.code(), note.argsJson(), french))
            .isFalse();
        assertThat(BillingTextRenderer.isShownText("Pansement", null, null, "Pansement simple")).isFalse();
        assertThat(BillingTextRenderer.isShownText(null, null, null, null)).isTrue();
        assertThat(BillingTextRenderer.isShownText(null, note.code(), note.argsJson(), french)).isFalse();
    }
}
