package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.UUID;

public interface AuditEventLogService {

    Page<AuditEventLogResponseDTO> getAuditLogsByUser(UUID userId, Pageable pageable);

    Page<AuditEventLogResponseDTO> getAuditLogsByTarget(String entityType, String resourceId, Pageable pageable);

    AuditEventLogResponseDTO logEvent(AuditEventRequestDTO requestDTO);

    /**
     * Record several events in ONE transaction, with the same per-event
     * resolution {@link #logEvent} does. A list read that discloses across
     * hospitals writes a row per patient; one committed transaction each
     * turned a worklist into hundreds.
     *
     * <p>The rows share a transaction and Hibernate flushes them at commit,
     * so a row that cannot be persisted takes that transaction with it. When
     * that happens the batch is <strong>replayed one event at a time</strong>,
     * each in its own transaction, so a single bad row costs one row rather
     * than the page — the fast path keeps its single commit and the
     * compliance rows survive the slow one.
     *
     * <p>It never throws, and it does not rely on its callers for that: the
     * batch runs in a transaction this method opens and commits itself, so
     * the commit failure is caught here rather than escaping to whoever was
     * being audited.
     */
    void logEvents(java.util.List<AuditEventRequestDTO> requestDTOs);

    Page<AuditEventLogResponseDTO> getAuditLogsByEventTypeAndStatus(AuditEventType parsedType, AuditStatus parsedStatus, Pageable pageable);

    Page<AuditEventLogResponseDTO> getAuditLogsByDateRange(LocalDateTime fromDate, LocalDateTime toDate, Pageable pageable);

    Page<AuditEventLogResponseDTO> getAuditLogsByHospital(UUID hospitalId, Pageable pageable);
}
