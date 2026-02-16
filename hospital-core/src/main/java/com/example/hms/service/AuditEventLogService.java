package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface AuditEventLogService {

    Page<AuditEventLogResponseDTO> getAuditLogsByUser(UUID userId, Pageable pageable);

    Page<AuditEventLogResponseDTO> getAuditLogsByTarget(String entityType, String resourceId, Pageable pageable);

    AuditEventLogResponseDTO logEvent(AuditEventRequestDTO requestDTO);

    Page<AuditEventLogResponseDTO> getAuditLogsByEventTypeAndStatus(AuditEventType parsedType, AuditStatus parsedStatus, Pageable pageable);
}
