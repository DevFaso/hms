package com.example.hms.utility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumbersTest {

    private static final String BF = "226";

    @Test
    @DisplayName("local, international and 00-prefixed spellings of one number all reach the same wire form")
    void allSpellingsAgree() {
        assertThat(PhoneNumbers.toInternationalDigits("70 70 70 70", BF)).isEqualTo("22670707070");
        assertThat(PhoneNumbers.toInternationalDigits("+22670707070", BF)).isEqualTo("22670707070");
        assertThat(PhoneNumbers.toInternationalDigits("+226 70 70 70 70", BF)).isEqualTo("22670707070");
        assertThat(PhoneNumbers.toInternationalDigits("0022670707070", BF)).isEqualTo("22670707070");
        assertThat(PhoneNumbers.toInternationalDigits("22670707070", BF)).isEqualTo("22670707070");
    }

    @Test
    @DisplayName("a number already longer than a local one is not given a second country code")
    void longNumbersUntouched() {
        assertThat(PhoneNumbers.toInternationalDigits("33612345678", BF)).isEqualTo("33612345678");
    }

    @Test
    @DisplayName("null, blank and digit-less input yield empty, never a bare country code")
    void nothingUsable() {
        assertThat(PhoneNumbers.toInternationalDigits(null, BF)).isEmpty();
        assertThat(PhoneNumbers.toInternationalDigits("  ", BF)).isEmpty();
        assertThat(PhoneNumbers.toInternationalDigits("abc", BF)).isEmpty();
    }
}
