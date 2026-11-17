package com.example.hms.utility;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.PatientVitalSign;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D1115 / D1002 class: the snapshot and worklist vitals line writes the temperature for the reader. */
class VitalsSummaryTextTest {

    private final MessageSource previous =
        (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");

    @BeforeEach
    void bundles() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
    }

    @AfterEach
    void restore() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void theTemperatureTakesTheReadersSeparator() {
        PatientVitalSign v = new PatientVitalSign();
        v.setTemperatureCelsius(37.9);
        v.setHeartRateBpm(88);
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(VitalsSummaryText.of(v)).startsWith("T° 37,9 °C");
        LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));
        assertThat(VitalsSummaryText.of(v)).contains("37,9 °C").doesNotContain("37.9");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(VitalsSummaryText.of(v)).startsWith("Temp 37.9 °C");
    }
}
