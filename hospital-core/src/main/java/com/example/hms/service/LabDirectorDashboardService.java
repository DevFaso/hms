package com.example.hms.service;

import com.example.hms.payload.dto.dashboard.LabDirectorDashboardDTO;

import java.util.UUID;

public interface LabDirectorDashboardService {

    /**
     * Build the Lab Director dashboard summary for the given hospital.
     *
     * @param hospitalId the active hospital context
     * @return populated dashboard DTO
     */
    LabDirectorDashboardDTO getSummary(UUID hospitalId);
}
