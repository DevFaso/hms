package com.example.hms.service;

import com.example.hms.payload.dto.dashboard.HospitalAdminSummaryDTO;

import java.time.LocalDate;
import java.util.UUID;

public interface HospitalAdminDashboardService {

    HospitalAdminSummaryDTO getSummary(UUID hospitalId, LocalDate asOfDate, int auditLimit);
}
