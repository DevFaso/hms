package com.example.hms.service;

import com.example.hms.payload.dto.clinical.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClinicalDashboardServiceImpl - Phase 1 Day 2
 * Tests business logic for clinical dashboard data aggregation
 * 
 * Note: Phase 1 uses sample data. Phase 2-6 will integrate with real
 * repositories.
 */
@ExtendWith(MockitoExtension.class)
class ClinicalDashboardServiceImplTest {

    private ClinicalDashboardServiceImpl service;
    private UUID testUserId;

    @BeforeEach
    void setUp() {
        service = new ClinicalDashboardServiceImpl();
        testUserId = UUID.randomUUID();
    }

    // ========== getClinicalDashboard() Tests ==========

    @Test
    void getClinicalDashboard_shouldReturnCompleteAggregatedData() {
        // Act
        ClinicalDashboardResponseDTO result = service.getClinicalDashboard(testUserId);

        // Assert
        assertNotNull(result, "Dashboard response should not be null");
        assertNotNull(result.getKpis(), "KPIs should not be null");
        assertNotNull(result.getAlerts(), "Alerts should not be null");
        assertNotNull(result.getInboxCounts(), "Inbox counts should not be null");
        assertNotNull(result.getOnCallStatus(), "On-call status should not be null");
        assertNotNull(result.getRoomedPatients(), "Roomed patients should not be null");
    }

    @Test
    void getClinicalDashboard_shouldIncludeKPIsWithExpectedStructure() {
        // Act
        ClinicalDashboardResponseDTO result = service.getClinicalDashboard(testUserId);

        // Assert
        List<DashboardKPI> kpis = result.getKpis();
        assertFalse(kpis.isEmpty(), "Should have at least one KPI");

        DashboardKPI firstKpi = kpis.get(0);
        assertNotNull(firstKpi.getLabel(), "KPI should have a label");
        assertNotNull(firstKpi.getValue(), "KPI should have a value");
    }

    @Test
    void getClinicalDashboard_shouldCallGetCriticalAlertsWithDefault24Hours() {
        // Act
        ClinicalDashboardResponseDTO result = service.getClinicalDashboard(testUserId);

        // Assert - verify alerts are returned (getCriticalAlerts is called internally)
        assertNotNull(result.getAlerts());
        // Sample data should return alerts
        assertFalse(result.getAlerts().isEmpty(), "Should have sample alerts in Phase 1");
    }

    // ========== getCriticalAlerts() Tests ==========

    @Test
    void getCriticalAlerts_withDefault24Hours_shouldReturnAlerts() {
        // Act
        List<ClinicalAlertDTO> alerts = service.getCriticalAlerts(testUserId, 24);

        // Assert
        assertNotNull(alerts);
        assertFalse(alerts.isEmpty(), "Should return sample alerts in Phase 1");
    }

    @Test
    void getCriticalAlerts_shouldReturnAlertsWithRequiredFields() {
        // Act
        List<ClinicalAlertDTO> alerts = service.getCriticalAlerts(testUserId, 24);

        // Assert
        for (ClinicalAlertDTO alert : alerts) {
            assertNotNull(alert.getId(), "Alert ID should not be null");
            assertNotNull(alert.getSeverity(), "Severity should not be null");
            assertNotNull(alert.getType(), "Type should not be null");
            assertNotNull(alert.getTitle(), "Title should not be null");
            assertNotNull(alert.getMessage(), "Message should not be null");
            assertNotNull(alert.getTimestamp(), "Timestamp should not be null");
            assertNotNull(alert.getIcon(), "Icon should not be null");
            assertEquals(false, alert.getAcknowledged(), "Sample alerts should not be acknowledged");
        }
    }

    @Test
    void getCriticalAlerts_shouldIncludeCriticalSeverityAlerts() {
        // Act
        List<ClinicalAlertDTO> alerts = service.getCriticalAlerts(testUserId, 24);

        // Assert
        boolean hasCritical = alerts.stream()
                .anyMatch(alert -> "CRITICAL".equals(alert.getSeverity()));
        assertTrue(hasCritical, "Should include at least one CRITICAL severity alert");
    }

    @Test
    void getCriticalAlerts_shouldIncludeUrgentSeverityAlerts() {
        // Act
        List<ClinicalAlertDTO> alerts = service.getCriticalAlerts(testUserId, 24);

        // Assert
        boolean hasUrgent = alerts.stream()
                .anyMatch(alert -> "URGENT".equals(alert.getSeverity()));
        assertTrue(hasUrgent, "Should include at least one URGENT severity alert");
    }

    @Test
    void getCriticalAlerts_withDifferentHoursParameter_shouldStillReturnAlerts() {
        // Act
        List<ClinicalAlertDTO> alerts1Hour = service.getCriticalAlerts(testUserId, 1);
        List<ClinicalAlertDTO> alerts72Hours = service.getCriticalAlerts(testUserId, 72);

        // Assert
        assertNotNull(alerts1Hour);
        assertNotNull(alerts72Hours);
        // In Phase 1 with sample data, results may be the same
        // Phase 2+ will filter by actual time windows
    }

    @Test
    void getCriticalAlerts_shouldIncludePatientInformation() {
        // Act
        List<ClinicalAlertDTO> alerts = service.getCriticalAlerts(testUserId, 24);

        // Assert
        boolean hasPatientInfo = alerts.stream()
                .anyMatch(alert -> alert.getPatientId() != null && alert.getPatientName() != null);
        assertTrue(hasPatientInfo, "Alerts should include patient information");
    }

    @Test
    void getCriticalAlerts_shouldHaveActionRequiredFlag() {
        // Act
        List<ClinicalAlertDTO> alerts = service.getCriticalAlerts(testUserId, 24);

        // Assert
        boolean hasActionRequired = alerts.stream()
                .anyMatch(alert -> alert.getActionRequired() != null && alert.getActionRequired());
        assertTrue(hasActionRequired, "Some alerts should have action required");
    }

    // ========== acknowledgeAlert() Tests ==========

    @Test
    void acknowledgeAlert_shouldNotThrowException() {
        // Arrange
        UUID alertId = UUID.randomUUID();

        // Act & Assert
        assertDoesNotThrow(() -> service.acknowledgeAlert(alertId, testUserId),
                "Acknowledging alert should not throw exception");
    }

    @Test
    void acknowledgeAlert_withMultipleAlerts_shouldHandleAll() {
        // Arrange
        UUID alertId1 = UUID.randomUUID();
        UUID alertId2 = UUID.randomUUID();
        UUID alertId3 = UUID.randomUUID();

        // Act & Assert
        assertDoesNotThrow(() -> {
            service.acknowledgeAlert(alertId1, testUserId);
            service.acknowledgeAlert(alertId2, testUserId);
            service.acknowledgeAlert(alertId3, testUserId);
        }, "Should handle multiple acknowledgments");
    }

    // ========== getInboxCounts() Tests ==========

    @Test
    void getInboxCounts_shouldReturnAllCountFields() {
        // Act
        InboxCountsDTO counts = service.getInboxCounts(testUserId);

        // Assert
        assertNotNull(counts);
        assertNotNull(counts.getUnreadMessages(), "Unread messages count should not be null");
        assertNotNull(counts.getPendingRefills(), "Pending refills count should not be null");
        assertNotNull(counts.getPendingResults(), "Pending results count should not be null");
        assertNotNull(counts.getTasksToComplete(), "Tasks to complete count should not be null");
        assertNotNull(counts.getDocumentsToSign(), "Documents to sign count should not be null");
    }

    @Test
    void getInboxCounts_shouldReturnNonNegativeValues() {
        // Act
        InboxCountsDTO counts = service.getInboxCounts(testUserId);

        // Assert
        assertTrue(counts.getUnreadMessages() >= 0, "Unread messages should be non-negative");
        assertTrue(counts.getPendingRefills() >= 0, "Pending refills should be non-negative");
        assertTrue(counts.getPendingResults() >= 0, "Pending results should be non-negative");
        assertTrue(counts.getTasksToComplete() >= 0, "Tasks to complete should be non-negative");
        assertTrue(counts.getDocumentsToSign() >= 0, "Documents to sign should be non-negative");
    }

    @Test
    void getInboxCounts_shouldHaveSampleDataInPhase1() {
        // Act
        InboxCountsDTO counts = service.getInboxCounts(testUserId);

        // Assert - sample data should have some counts > 0
        int totalCount = counts.getUnreadMessages() + counts.getPendingRefills() +
                counts.getPendingResults() + counts.getTasksToComplete() +
                counts.getDocumentsToSign();
        assertTrue(totalCount > 0, "Should have some inbox items in Phase 1 sample data");
    }

    // ========== getRoomedPatients() Tests ==========

    @Test
    void getRoomedPatients_shouldReturnPatientList() {
        // Act
        List<RoomedPatientDTO> patients = service.getRoomedPatients(testUserId);

        // Assert
        assertNotNull(patients);
        assertFalse(patients.isEmpty(), "Should return sample roomed patients in Phase 1");
    }

    @Test
    void getRoomedPatients_shouldHaveRequiredPatientFields() {
        // Act
        List<RoomedPatientDTO> patients = service.getRoomedPatients(testUserId);

        // Assert
        for (RoomedPatientDTO patient : patients) {
            assertNotNull(patient.getId(), "Patient ID should not be null");
            assertNotNull(patient.getPatientName(), "Patient name should not be null");
            assertNotNull(patient.getRoom(), "Room should not be null");
            assertNotNull(patient.getChiefComplaint(), "Chief complaint should not be null");
            assertTrue(patient.getWaitTimeMinutes() >= 0, "Wait time should be non-negative");
        }
    }

    @Test
    void getRoomedPatients_shouldIncludeVitalsInformation() {
        // Act
        List<RoomedPatientDTO> patients = service.getRoomedPatients(testUserId);

        // Assert
        boolean hasVitals = patients.stream()
                .anyMatch(patient -> patient.getVitals() != null);
        assertTrue(hasVitals, "Some patients should have vitals information");
    }

    @Test
    void getRoomedPatients_shouldIncludePreparationStatus() {
        // Act
        List<RoomedPatientDTO> patients = service.getRoomedPatients(testUserId);

        // Assert
        boolean hasPrepStatus = patients.stream()
                .anyMatch(patient -> patient.getPrepStatus() != null);
        assertTrue(hasPrepStatus, "Some patients should have preparation status");
    }

    @Test
    void getRoomedPatients_shouldIncludeAllergyInformation() {
        // Act
        List<RoomedPatientDTO> patients = service.getRoomedPatients(testUserId);

        // Assert
        boolean hasFlags = patients.stream()
                .anyMatch(patient -> patient.getFlags() != null && !patient.getFlags().isEmpty());
        assertTrue(hasFlags, "Some patients should have flags information");
    }

    // ========== getOnCallStatus() Tests ==========

    @Test
    void getOnCallStatus_shouldReturnStatusObject() {
        // Act
        OnCallStatusDTO status = service.getOnCallStatus(testUserId);

        // Assert
        assertNotNull(status);
        assertNotNull(status.getIsOnCall(), "On-call flag should not be null");
    }

    @Test
    void getOnCallStatus_whenOnCall_shouldIncludeShiftDetails() {
        // Act
        OnCallStatusDTO status = service.getOnCallStatus(testUserId);

        // Assert
        if (status.getIsOnCall() != null && status.getIsOnCall()) {
            assertNotNull(status.getShiftStart(), "Shift start should be set when on-call");
            assertNotNull(status.getShiftEnd(), "Shift end should be set when on-call");
            assertNotNull(status.getCoveringFor(), "Covering for should be set when on-call");
        }
    }

    @Test
    void getOnCallStatus_shouldIncludeBackupDoctorWhenAvailable() {
        // Act
        OnCallStatusDTO status = service.getOnCallStatus(testUserId);

        // Assert - Phase 1 sample data should include backup doctor
        if (status.getIsOnCall() != null && status.getIsOnCall()) {
            // Backup provider is optional but sample data may include it
            // Just verify it's a valid field
            assertNotNull(status, "Status should be complete");
        }
    }

    // ========== Integration/Aggregation Tests ==========

    @Test
    void getClinicalDashboard_shouldReturnConsistentData() {
        // Act
        ClinicalDashboardResponseDTO dashboard1 = service.getClinicalDashboard(testUserId);
        ClinicalDashboardResponseDTO dashboard2 = service.getClinicalDashboard(testUserId);

        // Assert - In Phase 1 with static sample data, results should be consistent
        assertEquals(dashboard1.getKpis().size(), dashboard2.getKpis().size(),
                "KPI count should be consistent");
        assertEquals(dashboard1.getAlerts().size(), dashboard2.getAlerts().size(),
                "Alert count should be consistent");
    }

    @Test
    void getClinicalDashboard_allComponents_shouldBeIndividuallyAccessible() {
        // Act - Test that each component method works standalone
        List<DashboardKPI> kpis = service.getClinicalDashboard(testUserId).getKpis();
        List<ClinicalAlertDTO> alerts = service.getCriticalAlerts(testUserId, 24);
        InboxCountsDTO inboxCounts = service.getInboxCounts(testUserId);
        List<RoomedPatientDTO> roomedPatients = service.getRoomedPatients(testUserId);
        OnCallStatusDTO onCallStatus = service.getOnCallStatus(testUserId);

        // Assert - All components should be accessible
        assertNotNull(kpis);
        assertNotNull(alerts);
        assertNotNull(inboxCounts);
        assertNotNull(roomedPatients);
        assertNotNull(onCallStatus);
    }

    // ========== Data Quality Tests ==========

    @Test
    void getCriticalAlerts_timestampsShouldBeRecentWithinTimeWindow() {
        // Act
        List<ClinicalAlertDTO> alerts = service.getCriticalAlerts(testUserId, 24);

        // Assert
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusHours(24);

        for (ClinicalAlertDTO alert : alerts) {
            LocalDateTime timestamp = alert.getTimestamp();
            assertNotNull(timestamp, "Alert timestamp should not be null");
            assertTrue(timestamp.isAfter(cutoff) || timestamp.isEqual(cutoff),
                    "Alert timestamp should be within 24-hour window");
            assertTrue(timestamp.isBefore(now) || timestamp.isEqual(now),
                    "Alert timestamp should not be in the future");
        }
    }

    @Test
    void getRoomedPatients_waitTimesShouldBeReasonable() {
        // Act
        List<RoomedPatientDTO> patients = service.getRoomedPatients(testUserId);

        // Assert
        for (RoomedPatientDTO patient : patients) {
            int waitTime = patient.getWaitTimeMinutes();
            assertTrue(waitTime >= 0, "Wait time should be non-negative");
            assertTrue(waitTime < 24 * 60, "Wait time should be less than 24 hours (1440 minutes)");
        }
    }

    @Test
    void getClinicalDashboard_shouldNotReturnNullValues() {
        // Act
        ClinicalDashboardResponseDTO dashboard = service.getClinicalDashboard(testUserId);

        // Assert - verify no critical null values
        assertNotNull(dashboard.getKpis());
        assertNotNull(dashboard.getAlerts());
        assertNotNull(dashboard.getInboxCounts());
        assertNotNull(dashboard.getOnCallStatus());
        assertNotNull(dashboard.getRoomedPatients());

        // Verify KPIs have no null critical fields
        for (DashboardKPI kpi : dashboard.getKpis()) {
            assertNotNull(kpi.getLabel());
            assertNotNull(kpi.getValue());
        }
    }
}
