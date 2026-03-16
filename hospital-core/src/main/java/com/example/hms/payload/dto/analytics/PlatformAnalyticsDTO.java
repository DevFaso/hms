package com.example.hms.payload.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformAnalyticsDTO {

    private long totalPatients;
    private long totalEncounters;
    private long totalAppointments;
    private long totalInvoices;
    private long totalLabOrders;
    private long totalPrescriptions;
    private long totalUsers;
    private long activeHospitals;

    private List<TrendPoint> appointmentTrend;
    private List<TrendPoint> encounterTrend;
    private List<TrendPoint> patientRegistrationTrend;

    private Map<String, Long> appointmentsByStatus;
    private Map<String, Long> encountersByStatus;
    private Map<String, Long> invoicesByStatus;

    private List<DepartmentUtilization> departmentUtilization;
    private List<HospitalMetric> hospitalMetrics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendPoint {
        private LocalDate date;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepartmentUtilization {
        private String departmentName;
        private long appointmentCount;
        private long encounterCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HospitalMetric {
        private String hospitalName;
        private long patientCount;
        private long appointmentCount;
        private long staffCount;
    }
}
