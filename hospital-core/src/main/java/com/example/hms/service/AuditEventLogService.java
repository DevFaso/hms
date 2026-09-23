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
     * <p><strong>All or nothing.</strong> The rows share a transaction and
     * Hibernate flushes them at commit, so a row that fails to persist takes
     * the whole batch with it — this method cannot offer {@link #logEvent}'s
     * per-event independence, and does not pretend to. A caller that needs
     * each event to stand or fall on its own must call {@link #logEvent} per
     * event and pay a transaction for each.
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
