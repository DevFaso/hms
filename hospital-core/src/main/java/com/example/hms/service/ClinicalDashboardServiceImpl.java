package com.example.hms.service;

import com.example.hms.payload.dto.clinical.ClinicalAlertDTO;
import com.example.hms.payload.dto.clinical.ClinicalDashboardResponseDTO;
import com.example.hms.payload.dto.clinical.DashboardKPI;
import com.example.hms.payload.dto.clinical.InboxCountsDTO;
import com.example.hms.payload.dto.clinical.OnCallStatusDTO;
import com.example.hms.payload.dto.clinical.RoomedPatientDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of Clinical Dashboard Service
 * Phase 1: Basic implementation with sample data
 * Future phases will add real database queries
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClinicalDashboardServiceImpl implements ClinicalDashboardService {

    @Override
    public ClinicalDashboardResponseDTO getClinicalDashboard(UUID userId) {
        log.info("Fetching clinical dashboard for user: {}", userId);

        return ClinicalDashboardResponseDTO.builder()
                .kpis(generateKPIs(userId))
                .alerts(getCriticalAlerts(userId, 24))
                .inboxCounts(getInboxCounts(userId))
                .onCallStatus(getOnCallStatus(userId))
                .roomedPatients(getRoomedPatients(userId))
                .build();
    }

    @Override
    public List<ClinicalAlertDTO> getCriticalAlerts(UUID userId, int hours) {
        log.info("Fetching critical alerts for user: {} within {} hours", userId, hours);

        // Phase 2: Replace with real database queries
        // For now, return sample data
        List<ClinicalAlertDTO> alerts = new ArrayList<>();

        // Sample critical lab result
        alerts.add(ClinicalAlertDTO.builder()
                .id(UUID.randomUUID())
                .severity("CRITICAL")
                .type("LAB_CRITICAL")
                .title("Critical Potassium Level")
                .message("Patient John Doe - K+ 6.5 mmol/L (Critical High)")
                .patientId(UUID.randomUUID())
                .patientName("John Doe")
                .timestamp(LocalDateTime.now().minusHours(2))
                .actionRequired(true)
                .acknowledged(false)
                .icon("🔴")
                .build());

        // Sample abnormal vitals
        alerts.add(ClinicalAlertDTO.builder()
                .id(UUID.randomUUID())
                .severity("URGENT")
                .type("VITAL_ABNORMAL")
                .title("Abnormal Blood Pressure")
                .message("Patient Jane Smith - BP 180/110 mmHg")
                .patientId(UUID.randomUUID())
                .patientName("Jane Smith")
                .timestamp(LocalDateTime.now().minusHours(1))
                .actionRequired(true)
                .acknowledged(false)
                .icon("⚠️")
                .build());

        return alerts;
    }

    @Override
    @Transactional
    public void acknowledgeAlert(UUID alertId, UUID userId) {
        log.info("Acknowledging alert: {} by user: {}", alertId, userId);
        // Phase 2: Update alert status in database
        // For now, just log
    }

    @Override
    public InboxCountsDTO getInboxCounts(UUID userId) {
        log.info("Fetching inbox counts for user: {}", userId);

        // Phase 2: Replace with real database queries
        return InboxCountsDTO.builder()
                .unreadMessages(5)
                .pendingRefills(3)
                .pendingResults(2)
                .tasksToComplete(7)
                .documentsToSign(1)
                .build();
    }

    @Override
    public List<RoomedPatientDTO> getRoomedPatients(UUID userId) {
        log.info("Fetching roomed patients for user: {}", userId);

        // Phase 2: Replace with real encounter queries
        List<RoomedPatientDTO> patients = new ArrayList<>();

        // Sample roomed patient
        Map<String, Object> vitals = new HashMap<>();
        vitals.put("temperature", 98.6);
        vitals.put("bloodPressure", "120/80");
        vitals.put("heartRate", 75);
        vitals.put("oxygenSaturation", 98);

        patients.add(RoomedPatientDTO.builder()
                .id(UUID.randomUUID())
                .encounterId(UUID.randomUUID())
                .patientName("John Doe")
                .age(45)
                .sex("M")
                .mrn("MRN-12345")
                .room("ER-101")
                .triageStatus("TRIAGED")
                .chiefComplaint("Chest pain")
                .waitTimeMinutes(15)
                .arrivalTime(LocalDateTime.now().minusMinutes(45))
                .vitals(vitals)
                .flags(Arrays.asList("Diabetes", "Hypertension"))
                .prepStatus(RoomedPatientDTO.PrepStatusDTO.builder()
                        .labsDrawn(true)
                        .imagingOrdered(false)
                        .consentSigned(true)
                        .build())
                .build());

        return patients;
    }

    @Override
    public OnCallStatusDTO getOnCallStatus(UUID userId) {
        log.info("Fetching on-call status for user: {}", userId);

        // Phase 2: Replace with real schedule queries
        return OnCallStatusDTO.builder()
                .isOnCall(false)
                .build();
    }

    /**
     * Generate KPI metrics for the dashboard
     */
    @SuppressWarnings("java:S1172") // userId will be used in Phase 2 for real KPI calculation
    private List<DashboardKPI> generateKPIs(UUID userId) {
        // Phase 2: Calculate real KPIs from database
        List<DashboardKPI> kpis = new ArrayList<>();

        kpis.add(DashboardKPI.builder()
                .key("patients_today")
                .label("Patients Today")
                .value(12)
                .unit("patients")
                .deltaNum(2.0)
                .trend("up")
                .build());

        kpis.add(DashboardKPI.builder()
                .key("appointments")
                .label("Appointments")
                .value(15)
                .unit("scheduled")
                .deltaNum(-1.0)
                .trend("down")
                .build());

        kpis.add(DashboardKPI.builder()
                .key("pending_results")
                .label("Pending Results")
                .value(8)
                .unit("results")
                .deltaNum(3.0)
                .trend("up")
                .build());

        kpis.add(DashboardKPI.builder()
                .key("tasks")
                .label("Tasks")
                .value(5)
                .unit("tasks")
                .deltaNum(0.0)
                .trend("stable")
                .build());

        return kpis;
    }
}
