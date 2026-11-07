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

    /** QA D1465: what parity bounds, by logic alone. */
    @org.junit.jupiter.api.Test
    void parityBoundsTheBirthsAndTheLivingChildren() {
        ObstetricCounts.requireConsistentWithParity(2, 1, 1, 2, 2, false, "h.");
        ObstetricCounts.requireConsistentWithParity(null, 9, 9, 9, 9, false, "h.");
        ObstetricCounts.requireConsistentWithParity(1, null, null, null, null, false, "h.");

        assertRefused(() -> ObstetricCounts.requireConsistentWithParity(1, 1, 1, null, null, false, "h."),
            "h.termBirths", "maternity.obstetric.termPretermAbovePara");
        assertRefused(() -> ObstetricCounts.requireConsistentWithParity(1, null, null, null, 2, false, "h."),
            "h.previousCesareanSections", "maternity.obstetric.cesareansAbovePara");
        assertRefused(() -> ObstetricCounts.requireConsistentWithParity(1, null, null, 7, null, false, "h."),
            "h.livingChildren", "maternity.obstetric.livingAbovePara");
    }

    /** A twin birth counts once in P, so P1 with two living children is a real history once it is stated. */
    @org.junit.jupiter.api.Test
    void aStatedMultipleBirthLetsLivingChildrenStandAboveParity() {
        ObstetricCounts.requireConsistentWithParity(1, 1, 0, 2, 0, true, "h.");
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field, String key) {
        org.assertj.core.api.Assertions.assertThatThrownBy(call)
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class, ex -> {
                org.assertj.core.api.Assertions.assertThat(ex.getField()).isEqualTo(field);
                org.assertj.core.api.Assertions.assertThat(ex.getMessage()).isEqualTo(key);
            });
    }
}
