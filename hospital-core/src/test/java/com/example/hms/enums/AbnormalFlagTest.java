package com.example.hms.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AbnormalFlagTest {

    /** B18 — direction is a refinement of ABNORMAL, never a fourth severity. */
    @Test
    void directionalValuesCollapseToAbnormal() {
        assertThat(AbnormalFlag.ABNORMAL_LOW.severity()).isEqualTo(AbnormalFlag.ABNORMAL);
        assertThat(AbnormalFlag.ABNORMAL_HIGH.severity()).isEqualTo(AbnormalFlag.ABNORMAL);
        assertThat(AbnormalFlag.ABNORMAL.severity()).isEqualTo(AbnormalFlag.ABNORMAL);
        assertThat(AbnormalFlag.NORMAL.severity()).isEqualTo(AbnormalFlag.NORMAL);
        assertThat(AbnormalFlag.CRITICAL.severity()).isEqualTo(AbnormalFlag.CRITICAL);
    }
}
