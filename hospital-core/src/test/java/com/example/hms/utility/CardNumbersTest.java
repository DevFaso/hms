package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static com.example.hms.testsupport.RandomIds.withoutRandomIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Billing-money plan, new acceptance criterion (2026-11-15): e-Keneya never takes a card number. */
@DisplayName("A value that looks like a payment card number is recognised")
class CardNumbersTest {

    @ParameterizedTest(name = "\"{0}\" holds a card number")
    @ValueSource(strings = {
        "4111111111111111",                 // Visa test number, 16 digits
        "4111 1111 1111 1111",              // spaces between groups
        "4111-1111-1111-1111",              // dashes between groups
        "378282246310005",                  // Amex, 15 digits
        "4222222222222",                    // Visa, 13 digits
        "6011111111111117",                 // Discover
        "carte 5555 5555 5555 4444 exp 12/27", // inside a sentence
        "Réf OM-1234 puis 4012888888881881",    // after an operator reference
    })
    void aLuhnValidRunOfThirteenToNineteenDigitsIsACardNumber(String text) {
        assertThat(CardNumbers.containsCardNumber(text)).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" holds no card number")
    @ValueSource(strings = {
        "",
        "   ",
        "OM-R20-77812",                     // an Orange Money reference
        "MP231117.1530.C12345",             // operator reference with dots and letters
        "4111111111111112",                 // 16 digits, Luhn fails
        "411111111111",                     // 12 digits
        "41111111111111111111",             // 20 digits
        "Trop-perçu sur le ticket modérateur", // a reason
        "123",                              // a CVV-like number cannot be told apart
        "2026-11-17",                       // a date
    })
    void anythingElseIsNot(String text) {
        assertThat(CardNumbers.containsCardNumber(text)).isFalse();
    }

    @Test
    void nullHoldsNone() {
        assertThat(CardNumbers.containsCardNumber(null)).isFalse();
        assertThatCode(() -> CardNumbers.requireNone(null, "reference")).doesNotThrowAnyException();
    }

    @Test
    void theRefusalNamesTheFieldAndNeverRepeatsTheValue() {
        assertThatThrownBy(() -> CardNumbers.requireNone("4111 1111 1111 1111", "notes"))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> {
                assertThat(refusal.getField()).isEqualTo("notes");
                assertThat(refusal.getMessageKey()).isEqualTo(com.example.hms.validation.NoCardNumber.MESSAGE_KEY);
                assertThat(withoutRandomIds(refusal.getMessage())).doesNotContain("4111").doesNotContain("1111");
            });
    }
}
