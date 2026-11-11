package com.example.hms.cdshooks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1133 (round 20): a computed quantity on a dose card has no padded zeros
 * ("8000 mg", "4 cp", "14 kg"), with the reader's separator ("12,5" in French
 * and Spanish). The paediatric dose-stop messages read "8000,00 mg" and
 * "4,00 cp ... pour 14,00 kg".
 */
class CdsCardMeasureTest {

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void aComputedQuantityHasNoPaddedZerosInAnyLanguage() {
        CdsCardText text = new CdsCardText(new StaticMessageSource());
        for (Locale locale : new Locale[] {Locale.FRENCH, Locale.forLanguageTag("es"), Locale.ENGLISH}) {
            LocaleContextHolder.setLocale(locale);
            assertThat(text.measure(8000.0, 2)).as(locale.toString()).isEqualTo("8000");
            assertThat(text.measure(4.0, 2)).as(locale.toString()).isEqualTo("4");
            assertThat(text.measure(14.0, 2)).as(locale.toString()).isEqualTo("14");
        }
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(text.measure(12.5, 2)).isEqualTo("12,5");
        assertThat(text.measure(0.125, 2)).isEqualTo("0,12");
        assertThat(text.measure(null, 2)).isEqualTo("—");
    }
}
