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
     * turned a worklist into hundreds. Individual failures are swallowed
     * exactly as they are for a single event.
     */
    void logEvents(java.util.List<AuditEventRequestDTO> requestDTOs);

    Page<AuditEventLogResponseDTO> getAuditLogsByEventTypeAndStatus(AuditEventType parsedType, AuditStatus parsedStatus, Pageable pageable);

    Page<AuditEventLogResponseDTO> getAuditLogsByDateRange(LocalDateTime fromDate, LocalDateTime toDate, Pageable pageable);

    Page<AuditEventLogResponseDTO> getAuditLogsByHospital(UUID hospitalId, Pageable pageable);
}
