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

    @Test
    @DisplayName("round 6: every spelling of one number is the same subscriber")
    void sameSubscriberAcrossSpellings() {
        assertThat(PhoneNumbers.isSameSubscriber("70 70 70 70", "+22670707070", BF)).isTrue();
        assertThat(PhoneNumbers.isSameSubscriber("+226 70 70 70 70", "22670707070", BF)).isTrue();
        assertThat(PhoneNumbers.isSameSubscriber("0022670707070", "+22670707070", BF)).isTrue();
    }

    @Test
    @DisplayName("round 6: a field holding two numbers matches either of them")
    void multiNumberFieldMatchesEither() {
        assertThat(PhoneNumbers.isSameSubscriber("70707070 / 70111222", "+22670707070", BF)).isTrue();
        assertThat(PhoneNumbers.isSameSubscriber("70707070 / 70111222", "+22670111222", BF)).isTrue();
        assertThat(PhoneNumbers.isSameSubscriber("+22670707070, +22670111222", "+22670111222", BF)).isTrue();
        assertThat(PhoneNumbers.isSameSubscriber("70707070 ou 70111222", "+22670111222", BF)).isTrue();
        // Two numbers with nothing but a space between them.
        assertThat(PhoneNumbers.isSameSubscriber("70707070 70111222", "+22670111222", BF)).isTrue();
        assertThat(PhoneNumbers.isSameSubscriber("70707070 70111222", "+22670707070", BF)).isTrue();
    }

    @Test
    @DisplayName("round 6: an extension after the number does not hide the subscriber")
    void extensionDoesNotHideTheSubscriber() {
        assertThat(PhoneNumbers.isSameSubscriber("+22670707070 poste 12", "+22670707070", BF)).isTrue();
        assertThat(PhoneNumbers.isSameSubscriber("70 70 70 70 ext. 3", "22670707070", BF)).isTrue();
    }

    @Test
    @DisplayName("round 6: a different subscriber is still a different subscriber")
    void differentNumbersDoNotMatch() {
        assertThat(PhoneNumbers.isSameSubscriber("70707070", "+22670111222", BF)).isFalse();
        assertThat(PhoneNumbers.isSameSubscriber("70707070", "+22670707079", BF)).isFalse();
        assertThat(PhoneNumbers.isSameSubscriber(null, "+22670707070", BF)).isFalse();
        assertThat(PhoneNumbers.isSameSubscriber("70707070", null, BF)).isFalse();
        assertThat(PhoneNumbers.isSameSubscriber("70707070", "  ", BF)).isFalse();
    }

    @Test
    @DisplayName("round 6: candidates lists every number a field holds, in wire form")
    void candidatesListsEveryNumber() {
        assertThat(PhoneNumbers.candidates("70707070 / 70111222", BF))
                .contains("22670707070", "22670111222");
        assertThat(PhoneNumbers.candidates(null, BF)).isEmpty();
        assertThat(PhoneNumbers.candidates("  ", BF)).isEmpty();
    }

    @Test
    @DisplayName("toE164: a local Burkina Faso number takes +226; spaced, 00 and + forms agree")
    void toE164NormalisesLocalNumbers() {
        assertThat(PhoneNumbers.toE164("70202020", BF)).isEqualTo("+22670202020");
        assertThat(PhoneNumbers.toE164("70 20 20 20", BF)).isEqualTo("+22670202020");
        assertThat(PhoneNumbers.toE164("0022670202020", BF)).isEqualTo("+22670202020");
        assertThat(PhoneNumbers.toE164("+226 70-20-20-20", BF)).isEqualTo("+22670202020");
        assertThat(PhoneNumbers.toE164("0612345678", "33")).isEqualTo("+33612345678");
    }

    @Test
    @DisplayName("toE164: letters, too few or too many digits, and a malformed +226 number are refused")
    void toE164RefusesInvalid() {
        assertThat(PhoneNumbers.toE164("abc", BF)).isNull();
        assertThat(PhoneNumbers.toE164("7020", BF)).isNull();
        assertThat(PhoneNumbers.toE164("702020201", BF)).isNull();
        assertThat(PhoneNumbers.toE164("+2267020202", BF)).isNull();
        assertThat(PhoneNumbers.toE164("+1234567890123456", BF)).isNull();
        assertThat(PhoneNumbers.toE164("  ", BF)).isNull();
    }

    @Test
    @DisplayName("callingCodeForCountry: Burkina Faso by name or code, and the default when unknown")
    void callingCodes() {
        assertThat(PhoneNumbers.callingCodeForCountry("Burkina Faso")).isEqualTo("226");
        assertThat(PhoneNumbers.callingCodeForCountry("BF")).isEqualTo("226");
        assertThat(PhoneNumbers.callingCodeForCountry("Côte d’Ivoire")).isEqualTo("225");
        assertThat(PhoneNumbers.callingCodeForCountry(null)).isEqualTo("226");
        assertThat(PhoneNumbers.callingCodeForCountry("Atlantis")).isEqualTo("226");
    }
}
