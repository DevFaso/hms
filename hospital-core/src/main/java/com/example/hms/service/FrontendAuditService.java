package com.example.hms.service;

import com.example.hms.payload.dto.FrontendAuditEventRequestDTO;

public interface FrontendAuditService {

    void recordEvent(FrontendAuditEventRequestDTO request, String ipAddress, String userAgent);
}
