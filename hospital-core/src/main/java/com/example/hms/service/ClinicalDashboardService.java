package com.example.hms.service;

import com.example.hms.payload.dto.clinical.*;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for Clinical Dashboard operations
 */
public interface ClinicalDashboardService {

    /**
     * Get comprehensive clinical dashboard data for a doctor
     *
     * @param userId the doctor's user ID
     * @return unified dashboard response with KPIs, alerts, counts, etc.
     */
    ClinicalDashboardResponseDTO getClinicalDashboard(UUID userId);

    /**
     * Get critical alerts for a doctor within specified hours
     *
     * @param userId the doctor's user ID
     * @param hours  time window in hours
     * @return list of critical alerts
     */
    List<ClinicalAlertDTO> getCriticalAlerts(UUID userId, int hours);

    /**
     * Acknowledge a clinical alert
     *
     * @param alertId the alert ID
     * @param userId  the acknowledging user ID
     */
    void acknowledgeAlert(UUID alertId, UUID userId);

    /**
     * Get inbox counts for a doctor
     *
     * @param userId the doctor's user ID
     * @return inbox counts
     */
    InboxCountsDTO getInboxCounts(UUID userId);

    /**
     * Get roomed patients for a doctor
     *
     * @param userId the doctor's user ID
     * @return list of roomed patients
     */
    List<RoomedPatientDTO> getRoomedPatients(UUID userId);

    /**
     * Get on-call status for a doctor
     *
     * @param userId the doctor's user ID
     * @return on-call status
     */
    OnCallStatusDTO getOnCallStatus(UUID userId);
}
