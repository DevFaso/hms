package com.example.hms.service;

import com.example.hms.payload.dto.dashboard.LabOpsSummaryDTO;

import java.util.UUID;

/**
 * Provides the Lab Operations Dashboard summary for a given hospital.
 */
public interface LabOpsDashboardService {

    /**
     * Build the lab operations summary for the given hospital.
     *
     * @param hospitalId the active hospital context
     * @return populated lab-ops summary DTO
     */
    LabOpsSummaryDTO getSummary(UUID hospitalId);
}
