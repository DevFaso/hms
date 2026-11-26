package com.example.hms.service.scheduled;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The retention job: never below six years, off unless enabled, and batches until the backlog is done. */
class AuditRetentionPurgeJobTest {

    private static final String CALL = "SELECT support.purge_expired_audit_rows(?, ?)";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void aRetentionUnderSixYearsStopsTheApplication() {
        assertThatThrownBy(() -> new AuditRetentionPurgeJob(jdbc, false, 5, 1000, 100))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(AuditRetentionPurgeJob.RETENTION_BELOW_MINIMUM);
    }

    @Test
    void disabledByDefaultItTouchesNothing() {
        AuditRetentionPurgeJob job = new AuditRetentionPurgeJob(jdbc, false, 6, 1000, 100);

        assertThat(job.purgeExpiredAuditRows()).isZero();
        verifyNoInteractions(jdbc);
    }

    @Test
    void itCallsTheDatabaseFunctionUntilABatchComesBackShort() {
        when(jdbc.queryForObject(eq(CALL), eq(Long.class), eq(7), eq(100))).thenReturn(100L, 100L, 42L);
        AuditRetentionPurgeJob job = new AuditRetentionPurgeJob(jdbc, true, 7, 100, 50);

        assertThat(job.purgeExpiredAuditRows()).isEqualTo(242L);
        verify(jdbc, times(3)).queryForObject(eq(CALL), eq(Long.class), eq(7), eq(100));
    }

    @Test
    void oneRunStopsAtMaxBatches() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(6), eq(10))).thenReturn(10L);
        AuditRetentionPurgeJob job = new AuditRetentionPurgeJob(jdbc, true, 6, 10, 3);

        assertThat(job.purgeExpiredAuditRows()).isEqualTo(30L);
        verify(jdbc, times(3)).queryForObject(eq(CALL), eq(Long.class), eq(6), eq(10));
    }
}
