package com.example.hms.service.pharmacy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** D1002 class (round 19): the remainder on the prescription copy and the partner SMS, in the reader's notation. */
class FillAccountingRemainingLabelTest {

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
