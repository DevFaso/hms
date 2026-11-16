package com.example.hms.service;

import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;

/** Sensitive identity operations require an identifiable human actor and atomic auditing. */
public interface RequiredAuditEventLogPort {
    AuditEventLogResponseDTO logRequiredEvent(AuditEventRequestDTO request);
}
