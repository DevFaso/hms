package com.example.hms.service;

import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;
import com.example.hms.payload.dto.SuperAdminSummaryDTO;

import java.util.List;
import java.util.Locale;

public interface SuperAdminDashboardService {
    SuperAdminSummaryDTO getSummary(int recentAuditLimit);

    List<EncounterResponseDTO> getRecentEncounters(int limit, Locale locale);

    List<StaffAvailabilityResponseDTO> getRecentStaffAvailability(int limit);

    List<PatientConsentResponseDTO> getRecentPatientConsents(int limit);
}
