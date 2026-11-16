package com.example.hms.service;

import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;

/** Sensitive identity operations and their audit must commit or roll back together. */
public interface RequiredAuditEventLogPort {
    AuditEventLogResponseDTO logRequiredEvent(AuditEventRequestDTO request);
}
