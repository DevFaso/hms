package com.example.hms.service;

import com.example.hms.repository.AuditEventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Scheduled job that enforces audit log retention policy.
 * HIPAA requires a minimum of 6 years (2190 days) of audit log retention.
 * Logs older than the configured retention period are deleted nightly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogRetentionScheduler {

    private final AuditEventLogRepository auditRepository;

    @Value("${app.audit.retention-days:2190}")
    private int retentionDays;

    @Scheduled(cron = "${app.audit.retention-cron:0 0 2 * * *}")
    @Transactional
    public void purgeExpiredAuditLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = auditRepository.deleteByEventTimestampBefore(cutoff);

        if (deleted == 0) {
            log.debug("[AUDIT RETENTION] No audit logs older than {} days found.", retentionDays);
        } else {
            log.info("[AUDIT RETENTION] Purged {} audit log entries older than {} (retention={} days).",
                    deleted, cutoff, retentionDays);
        }
    }
}
