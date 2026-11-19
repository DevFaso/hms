package com.example.hms.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** r63 D2304: +/-2 SD and +/-3 SD flags, only when a usable target is set. */
class LabQcStatisticsTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void flagsByDistanceFromTheTargetMeanInSds() {
        assertThat(LabQcStatistics.sdFlag(d("5.0"), d("5.0"), d("0.2"))).isEqualTo(LabQcStatistics.WITHIN_2SD);
        assertThat(LabQcStatistics.sdFlag(d("5.4"), d("5.0"), d("0.2"))).isEqualTo(LabQcStatistics.WITHIN_2SD);
        assertThat(LabQcStatistics.sdFlag(d("5.5"), d("5.0"), d("0.2"))).isEqualTo(LabQcStatistics.OUTSIDE_2SD);
        assertThat(LabQcStatistics.sdFlag(d("4.3"), d("5.0"), d("0.2"))).isEqualTo(LabQcStatistics.OUTSIDE_3SD);
        assertThat(LabQcStatistics.zScore(d("5.5"), d("5.0"), d("0.2"))).isEqualTo(2.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void noTargetNoFlag() {
        assertThat(LabQcStatistics.sdFlag(d("5.0"), null, null)).isNull();
        assertThat(LabQcStatistics.sdFlag(d("5.0"), d("5.0"), d("0"))).isNull();
        assertThat(LabQcStatistics.zScore(d("5.0"), d("5.0"), null)).isNull();
    }
}
