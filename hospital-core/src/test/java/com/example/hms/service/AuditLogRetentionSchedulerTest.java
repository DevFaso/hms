package com.example.hms.service;

import com.example.hms.repository.AuditEventLogRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogRetentionSchedulerTest {

    @Mock private AuditEventLogRepository auditRepository;

    @InjectMocks private AuditLogRetentionScheduler scheduler;

    @ParameterizedTest(name = "retentionDays={0}, deletedCount={1}")
    @CsvSource({"2190, 42", "2190, 0", "365, 10"})
    void purgeExpiredAuditLogs_deletesBasedOnRetention(int retentionDays, int deletedCount) {
        ReflectionTestUtils.setField(scheduler, "retentionDays", retentionDays);
        when(auditRepository.deleteByEventTimestampBefore(any(LocalDateTime.class))).thenReturn(deletedCount);

        scheduler.purgeExpiredAuditLogs();

        verify(auditRepository).deleteByEventTimestampBefore(any(LocalDateTime.class));
    }
}
