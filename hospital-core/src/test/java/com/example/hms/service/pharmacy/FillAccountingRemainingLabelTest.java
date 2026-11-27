package com.example.hms.service.pharmacy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** D1002 class (round 19): the remainder on the prescription copy and the partner SMS, in the reader's notation. */
class FillAccountingRemainingLabelTest {

    @Test
    void countedUnitsAgreeWithTheQuantityWithoutChangingMeasurementUnits() {
        assertThat(FillAccounting.remainingLabel(new BigDecimal("21"), "gélule", Locale.FRENCH)).isEqualTo("21 gélules");
        assertThat(FillAccounting.remainingLabel(new BigDecimal("2"), "plaquette", Locale.FRENCH)).isEqualTo("2 plaquettes");
        assertThat(FillAccounting.remainingLabel(new BigDecimal("0"), "comprimés", Locale.FRENCH)).isEqualTo("0 comprimé");
        assertThat(FillAccounting.remainingLabel(new BigDecimal("1"), "boxes", Locale.ENGLISH)).isEqualTo("1 box");
        assertThat(FillAccounting.remainingLabel(new BigDecimal("2"), "mg", Locale.FRENCH)).isEqualTo("2 mg");
        assertThat(FillAccounting.remainingLabel(new BigDecimal("2"), "unknown unit", Locale.FRENCH)).isEqualTo("2 unknown unit");
    }

    @Test
    void theRemainderTakesTheReadersSeparatorAndDropsTrailingZeros() {
        assertThat(FillAccounting.remainingLabel(new BigDecimal("2.50"), "ml", Locale.FRENCH)).isEqualTo("2,5 ml");
        assertThat(FillAccounting.remainingLabel(new BigDecimal("2.50"), "ml", Locale.ENGLISH)).isEqualTo("2.5 ml");
        assertThat(FillAccounting.remainingLabel(new BigDecimal("6.00"), " comprimés ", Locale.FRENCH))
            .isEqualTo("6 comprimés");
        assertThat(FillAccounting.remainingLabel(new BigDecimal("6"), null, Locale.FRENCH)).isEqualTo("6");
        assertThat(FillAccounting.remainingLabel(null, "ml", Locale.FRENCH)).isNull();
    }
}
