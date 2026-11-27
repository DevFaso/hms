package com.example.hms.service.clinicalrules;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClinicalOverrideReasonTest {
    @Test
    void countsNonWhitespaceCharactersRatherThanPadding() {
        assertThat(ClinicalOverrideReason.isSufficient(null)).isFalse();
        assertThat(ClinicalOverrideReason.isSufficient("ok")).isFalse();
        assertThat(ClinicalOverrideReason.isSufficient("          ")).isFalse();
        assertThat(ClinicalOverrideReason.isSufficient("123456789   ")).isFalse();
        assertThat(ClinicalOverrideReason.isSufficient("12345\u00a0 67890")).isTrue();
        assertThat(ClinicalOverrideReason.isSufficient("a\u00a0 b c d e f g h i")).isFalse();
    }
}
