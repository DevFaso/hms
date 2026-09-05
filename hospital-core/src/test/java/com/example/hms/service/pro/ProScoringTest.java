package com.example.hms.service.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.pro.ProInstrument;
import com.example.hms.model.pro.ProInstrumentItem;
import com.example.hms.model.pro.ProInstrumentOption;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The engine sums what the rows say; the fixture is deliberately NOT the
 * EPDS — three items with made-up scores prove the arithmetic without
 * putting any instrument content in code.
 */
class ProScoringTest {

    /** Three items, four options each (scores 0..3), item 3 is the critical one, threshold 5. */
    private static ProInstrument fixture() {
        ProInstrument instrument = ProInstrument.builder()
            .code("TEST")
            .positiveThreshold(5)
            .criticalItemNo(3)
            .build();
        for (int itemNo = 1; itemNo <= 3; itemNo++) {
            ProInstrumentItem item = ProInstrumentItem.builder().instrument(instrument).itemNo(itemNo).build();
            for (int optionNo = 1; optionNo <= 4; optionNo++) {
                item.getOptions().add(ProInstrumentOption.builder()
                    .item(item).optionNo(optionNo).score(optionNo - 1).build());
            }
            instrument.getItems().add(item);
        }
        return instrument;
    }

    @Test
    void sumsTheChosenOptionScores() {
        ProScoring.ProScoreResult result = ProScoring.score(fixture(), Map.of(1, 2, 2, 3, 3, 1));

        assertThat(result.totalScore()).isEqualTo(1 + 2 + 0);
        assertThat(result.answeredItems()).isEqualTo(3);
        assertThat(result.totalItems()).isEqualTo(3);
        assertThat(result.complete()).isTrue();
        assertThat(result.missingItems()).isEmpty();
        assertThat(result.screenPositive()).isFalse();
        assertThat(result.criticalItemScore()).isZero();
        assertThat(result.criticalPositive()).isFalse();
    }

    @Test
    void thresholdIsInclusive() {
        ProScoring.ProScoreResult atThreshold = ProScoring.score(fixture(), Map.of(1, 3, 2, 4, 3, 1));
        assertThat(atThreshold.totalScore()).isEqualTo(5);
        assertThat(atThreshold.screenPositive()).isTrue();

        ProScoring.ProScoreResult below = ProScoring.score(fixture(), Map.of(1, 3, 2, 3, 3, 1));
        assertThat(below.totalScore()).isEqualTo(4);
        assertThat(below.screenPositive()).isFalse();
    }

    @Test
    void anyNonZeroScoreOnTheCriticalItemIsPositiveRegardlessOfTotal() {
        ProScoring.ProScoreResult result = ProScoring.score(fixture(), Map.of(1, 1, 2, 1, 3, 2));

        assertThat(result.totalScore()).isEqualTo(1);
        assertThat(result.screenPositive()).isFalse();
        assertThat(result.criticalItemScore()).isEqualTo(1);
        assertThat(result.criticalPositive()).isTrue();
    }

    @Test
    void instrumentWithoutCriticalItemNeverFlagsCritical() {
        ProInstrument instrument = fixture();
        instrument.setCriticalItemNo(null);

        ProScoring.ProScoreResult result = ProScoring.score(instrument, Map.of(1, 4, 2, 4, 3, 4));

        assertThat(result.criticalItemScore()).isNull();
        assertThat(result.criticalPositive()).isFalse();
        assertThat(result.screenPositive()).isTrue();
    }

    @Test
    void unansweredItemsAreNamedAndTheTotalIsALowerBound() {
        ProScoring.ProScoreResult result = ProScoring.score(fixture(), Map.of(1, 4));

        assertThat(result.totalScore()).isEqualTo(3);
        assertThat(result.answeredItems()).isEqualTo(1);
        assertThat(result.complete()).isFalse();
        assertThat(result.missingItems()).containsExactly(2, 3);
        // The critical item was not answered: nothing to say about it.
        assertThat(result.criticalItemScore()).isNull();
        assertThat(result.criticalPositive()).isFalse();
    }

    @Test
    void nullAnswersMeansEverythingMissing() {
        ProScoring.ProScoreResult result = ProScoring.score(fixture(), null);

        assertThat(result.totalScore()).isZero();
        assertThat(result.missingItems()).containsExactly(1, 2, 3);
    }

    @Test
    void rejectsAnItemTheInstrumentDoesNotHave() {
        assertThatThrownBy(() -> ProScoring.score(fixture(), Map.of(1, 1, 9, 1)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no item 9");
    }

    @Test
    void rejectsAnOptionTheItemDoesNotHave() {
        assertThatThrownBy(() -> ProScoring.score(fixture(), Map.of(2, 5)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("item 2 has no option 5");
    }

    @Test
    void rejectsANullItemKey() {
        Map<Integer, Integer> answers = new HashMap<>();
        answers.put(null, 1);

        assertThatThrownBy(() -> ProScoring.score(fixture(), answers))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAnInstrumentWithNoItemsLoaded() {
        ProInstrument empty = ProInstrument.builder().code("EMPTY").positiveThreshold(1).build();

        assertThatThrownBy(() -> ProScoring.score(empty, Map.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no items loaded");
    }
}
