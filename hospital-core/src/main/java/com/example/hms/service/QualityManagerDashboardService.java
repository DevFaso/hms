package com.example.hms.service;

import com.example.hms.payload.dto.dashboard.QualityManagerDashboardDTO;

import java.util.UUID;

public interface QualityManagerDashboardService {

    /**
     * Build the Quality Manager dashboard summary for the given hospital.
     *
     * @param hospitalId the active hospital context
     * @return populated dashboard DTO
     */
    QualityManagerDashboardDTO getSummary(UUID hospitalId);
}
