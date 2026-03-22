package com.example.hms.service;

import com.example.hms.repository.AuditEventLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Test
    void purgeExpiredAuditLogs_deletesExpiredRecords() {
        ReflectionTestUtils.setField(scheduler, "retentionDays", 2190);
        when(auditRepository.deleteByEventTimestampBefore(any(LocalDateTime.class))).thenReturn(42);

        scheduler.purgeExpiredAuditLogs();

        verify(auditRepository).deleteByEventTimestampBefore(any(LocalDateTime.class));
    }

    @Test
    void purgeExpiredAuditLogs_noExpiredRecords() {
        ReflectionTestUtils.setField(scheduler, "retentionDays", 2190);
        when(auditRepository.deleteByEventTimestampBefore(any(LocalDateTime.class))).thenReturn(0);

        scheduler.purgeExpiredAuditLogs();

        verify(auditRepository).deleteByEventTimestampBefore(any(LocalDateTime.class));
    }

    @Test
    void purgeExpiredAuditLogs_customRetentionDays() {
        ReflectionTestUtils.setField(scheduler, "retentionDays", 365);
        when(auditRepository.deleteByEventTimestampBefore(any(LocalDateTime.class))).thenReturn(10);

        scheduler.purgeExpiredAuditLogs();

        verify(auditRepository).deleteByEventTimestampBefore(any(LocalDateTime.class));
    }
}
