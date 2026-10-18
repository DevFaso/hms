package com.example.hms.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D144: the summary's grouped date column arrives as LocalDate, not java.sql.Date. */
class HospitalAdminDashboardDateColumnTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);

    @Test
    @DisplayName("a LocalDate row (the shape that answered 500) is read as is")
    void localDateRow() {
        assertThat(HospitalAdminDashboardServiceImpl.asLocalDate(DAY)).isEqualTo(DAY);
    }

    @Test
    @DisplayName("java.sql.Date, Timestamp and LocalDateTime rows still work")
    void otherShapes() {
        assertThat(HospitalAdminDashboardServiceImpl.asLocalDate(java.sql.Date.valueOf(DAY))).isEqualTo(DAY);
        assertThat(HospitalAdminDashboardServiceImpl.asLocalDate(
            java.sql.Timestamp.valueOf(DAY.atTime(9, 30)))).isEqualTo(DAY);
        assertThat(HospitalAdminDashboardServiceImpl.asLocalDate(LocalDateTime.of(2026, 10, 18, 9, 30)))
            .isEqualTo(DAY);
        assertThat(HospitalAdminDashboardServiceImpl.asLocalDate(null)).isNull();
    }
}
