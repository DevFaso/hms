package com.example.hms.service.orderset;

import com.example.hms.exception.FieldBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D1419 / D1426 (round 34): an order-set item goes through the three outcomes
 * of a frequency as an order does, at save and at apply: a schedule passes, a
 * condition is refused on the tick, anything else is asked about once on the
 * item's own {@code frequencyConfirmed}, and the answer stays on the item.
 */
class OrderSetItemPrnRulesTest {

    private static Map<String, Object> item(String frequency) {
        return Map.of("orderType", "MEDICATION", "medicationName", "Fansidar", "frequency", frequency);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QD", "BID", "TID", "QID", "Q4H", "Q6H", "Q8H", "prendre 1 comprimé 3 fois par jour",
        "H0 H8 H24", "J1 J2 J3", "au coucher", "TPI-SP", "perfusion continue"})
    void aScheduleIsNeverAsked(String frequency) {
        assertThatCode(() -> OrderSetItemPrnRules.requireValid(List.of(item(frequency)))).doesNotThrowAnyException();
    }

    @Test
    void aWordingNotReadIsAskedOnTheItemsOwnField() {
        assertThatThrownBy(() -> OrderSetItemPrnRules.requireValid(List.of(item("QD"), item("SP + AQ"))))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "orderItems[1].frequencyConfirmed");
    }

    @Test
    void theAuthorsAnswerStaysOnTheItem() {
        Map<String, Object> confirmed = Map.of("orderType", "MEDICATION", "medicationName", "Fansidar",
            "frequency", "SP + AQ", "frequencyConfirmed", true);
        assertThat(OrderSetItemPrnRules.frequencyConfirmed(confirmed)).isTrue();
        assertThat(OrderSetItemPrnRules.frequencyConfirmed(item("SP + AQ"))).isFalse();
        assertThatCode(() -> OrderSetItemPrnRules.requireValid(List.of(confirmed))).doesNotThrowAnyException();
    }

    /** D1395: a condition is not answered by confirming it, on an item either. */
    @Test
    void aConditionStaysRefusedOnTheTick() {
        Map<String, Object> confirmed = Map.of("orderType", "MEDICATION", "medicationName", "Morphine",
            "frequency", "toutes les 6 h pour douleur", "frequencyConfirmed", true);
        assertThatThrownBy(() -> OrderSetItemPrnRules.requireValid(List.of(confirmed)))
            .hasFieldOrPropertyWithValue("field", "orderItems[0].asNeeded");
    }
}
