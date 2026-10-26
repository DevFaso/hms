package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D809: the one rule for gravidity, parity and abortions, wherever they are written. */
class ObstetricCountsTest {

    @Test
    void parityAboveGravidityIsRefusedOnThePrefixedParaField() {
        assertThatThrownBy(() -> ObstetricCounts.requireConsistent(1, 3, null, "obstetricHistory."))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "obstetricHistory.para");
    }

    @Test
    void birthsPlusAbortionsAboveGravidityAreRefusedOnTheAbortionsField() {
        assertThatThrownBy(() -> ObstetricCounts.requireConsistent(3, 2, 2, ""))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "abortions");
        assertThatThrownBy(() -> ObstetricCounts.requireConsistent(1, null, 2, ""))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "abortions");
    }

    @Test
    void possibleHistoriesAndBlankCountsAreAccepted() {
        assertThatCode(() -> ObstetricCounts.requireConsistent(3, 2, 1, "")).doesNotThrowAnyException();
        assertThatCode(() -> ObstetricCounts.requireConsistent(1, 1, 0, "")).doesNotThrowAnyException();
        assertThatCode(() -> ObstetricCounts.requireConsistent(1, 0, null, "")).doesNotThrowAnyException();
        assertThatCode(() -> ObstetricCounts.requireConsistent(null, 3, 2, "")).doesNotThrowAnyException();
        assertThatCode(() -> ObstetricCounts.requireConsistent(2, null, null, null)).doesNotThrowAnyException();
    }
}
