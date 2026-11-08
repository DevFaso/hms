package com.example.hms.utility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D1106 (D1002 class): the one server formatter for a number a person reads. */
class DisplayNumbersTest {

    private static final Locale FR = Locale.FRENCH;
    private static final Locale ES = Locale.forLanguageTag("es");
    private static final Locale EN = Locale.ENGLISH;

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("a lab value takes the reader's separator and keeps the laboratory's precision")
    void labValue() {
        assertThat(DisplayNumbers.labValue("5.9", FR)).isEqualTo("5,9");
        assertThat(DisplayNumbers.labValue("5.9", ES)).isEqualTo("5,9");
        assertThat(DisplayNumbers.labValue("5.9", EN)).isEqualTo("5.9");
        assertThat(DisplayNumbers.labValue("0.40", FR)).isEqualTo("0,40");
        assertThat(DisplayNumbers.labValue("0.320", ES)).isEqualTo("0,320");
        assertThat(DisplayNumbers.labValue("7,9", EN)).isEqualTo("7.9");
    }

    @Test
    @DisplayName("integers, comparators, words, ranges and null")
    void labValueEdges() {
        assertThat(DisplayNumbers.labValue("12", FR)).isEqualTo("12");
        assertThat(DisplayNumbers.labValue("250000", FR)).isEqualTo("250000");
        assertThat(DisplayNumbers.labValue("<0.5", FR)).isEqualTo("<0,5");
        assertThat(DisplayNumbers.labValue("≥ 1.2", ES)).isEqualTo("≥ 1,2");
        assertThat(DisplayNumbers.labValue("-1.5", FR)).endsWith("1,5");
        assertThat(DisplayNumbers.labValue("Positif", FR)).isEqualTo("Positif");
        assertThat(DisplayNumbers.labValue("1/64", FR)).isEqualTo("1/64");
        assertThat(DisplayNumbers.labValue("3.5 - 5", FR)).isEqualTo("3,5 - 5");
        assertThat(DisplayNumbers.labValue(null, FR)).isEmpty();
    }

    @Test
    @DisplayName("with a unit, and in the request's locale")
    void withUnitAndRequestLocale() {
        assertThat(DisplayNumbers.labValueWithUnit("6.8", "g/dL", FR)).isEqualTo("6,8 g/dL");
        assertThat(DisplayNumbers.labValueWithUnit("6.8", " ", FR)).isEqualTo("6,8");
        assertThat(DisplayNumbers.labValueWithUnit(null, "g/dL", FR)).isEmpty();
        LocaleContextHolder.setLocale(ES);
        assertThat(DisplayNumbers.labValue("5.6")).isEqualTo("5,6");
        assertThat(DisplayNumbers.quantity(new BigDecimal("2.50"))).isEqualTo("2,5");
    }

    @Test
    @DisplayName("decimals, quantities and measures: no grouping, the separator of the locale")
    void numbers() {
        assertThat(DisplayNumbers.decimal(38.94, 1, FR)).isEqualTo("38,9");
        assertThat(DisplayNumbers.decimal(1250.0, 2, FR)).isEqualTo("1250,00");
        assertThat(DisplayNumbers.decimal(2.5, 2, EN)).isEqualTo("2.50");
        assertThat(DisplayNumbers.decimal(null, 1, FR)).isEqualTo("—");
        assertThat(DisplayNumbers.quantity(new BigDecimal("2.50"), FR)).isEqualTo("2,5");
        assertThat(DisplayNumbers.quantity(new BigDecimal("3.000"), FR)).isEqualTo("3");
        assertThat(DisplayNumbers.quantity(new BigDecimal("1000000"), FR)).isEqualTo("1000000");
        assertThat(DisplayNumbers.quantity(null, FR)).isEmpty();
        assertThat(DisplayNumbers.measure(37.9, 1, FR)).isEqualTo("37,9");
        assertThat(DisplayNumbers.measure(37.0, 1, ES)).isEqualTo("37");
        assertThat(DisplayNumbers.measure(37.94, 1, EN)).isEqualTo("37.9");
        assertThat(DisplayNumbers.measure(null, 1, FR)).isEmpty();
        assertThat(DisplayNumbers.localizeDecimals("12 - 15.5 g/dL", ES)).isEqualTo("12 - 15,5 g/dL");
        assertThat(DisplayNumbers.localizeDecimals("12 - 15.5 g/dL", EN)).isEqualTo("12 - 15.5 g/dL");
    }
}
