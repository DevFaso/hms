package com.example.hms.payload.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HospitalAdminSummaryDTO {

    private UUID hospitalId;
    private LocalDate asOfDate;

    private AppointmentMetrics appointments;
    private AdmissionMetrics admissions;
    private ConsultationMetrics consultations;
    private StaffingMetrics staffing;
    private BillingMetrics billing;
    private List<AuditSnippet> recentAuditEvents;

    // MVP 17 additions
    private List<DepartmentStaffingRow> staffingByDepartment;
    private List<ConsultBacklogItem> consultBacklog;
    private List<AuditDayCount> auditTrend;

    // MVP 18 additions
    private InvoiceAgingBuckets invoiceAging;
    private List<IntegrationStatusRow> integrations;
    private BigDecimal paymentCollectionRate;

    // MVP 19 additions
    private List<LicenseExpiryAlert> licenseAlerts;
    private LeaveMetrics leave;

    // MVP 20 additions
    private List<PaymentTrendPoint> paymentTrend;
    private List<PaymentMethodBreakdown> paymentMethodBreakdown;
    private WriteOffSummary writeOffs;

    // MVP 21: Bed/Ward inventory
    private BedOccupancy bedOccupancy;
    private List<WardOccupancyRow> wardOccupancy;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AppointmentMetrics {
        private long today;
        private long completed;
        private long noShows;
        private long cancelled;
        private long pending;
        private long inProgress;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdmissionMetrics {
        private long active;
        private long admittedToday;
        private long dischargedToday;
        private long awaitingDischarge;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsultationMetrics {
        private long requested;
        private long acknowledged;
        private long inProgress;
        private long overdue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StaffingMetrics {
        private long activeStaff;
        private long onShiftToday;
        private long staffOnLeaveToday;
        private long upcomingLeave;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BillingMetrics {
        private long overdueInvoices;
        private BigDecimal openBalanceTotal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditSnippet {
        private String id;
        private String eventType;
        private String status;
        private String entityType;
        private String resourceName;
        private String userName;
        private LocalDateTime eventTimestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepartmentStaffingRow {
        private String departmentId;
        private String departmentName;
        private long scheduledShifts;
        private long cancelledShifts;
        private long activeStaff;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsultBacklogItem {
        private String consultationId;
        private String patientName;
        private String specialtyRequested;
        private String urgency;
        private String status;
        private LocalDateTime requestedAt;
        private LocalDateTime slaDueBy;
        private boolean overdue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditDayCount {
        private LocalDate date;
        private long count;
    }

    // ── MVP 18 inner classes ─────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvoiceAgingBucket {
        private String label;
        private long count;
        private BigDecimal total;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvoiceAgingBuckets {
        private InvoiceAgingBucket current;
        private InvoiceAgingBucket days1to30;
        private InvoiceAgingBucket days31to60;
        private InvoiceAgingBucket days61to90;
        private InvoiceAgingBucket over90;
        private BigDecimal grandTotal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntegrationStatusRow {
        private String serviceType;
        private String provider;
        private String status;
        private boolean enabled;
        private String baseUrl;
    }

    // ── MVP 19 inner classes ─────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LicenseExpiryAlert {
        private String staffId;
        private String staffName;
        private String jobTitle;
        private String departmentName;
        private String licenseNumber;
        private LocalDate licenseExpiryDate;
        private String severity; // EXPIRED, CRITICAL, WARNING
        private long daysUntilExpiry;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeaveMetrics {
        private long onLeaveToday;
        private long upcomingLeaveNext7Days;
    }

    // ── MVP 20 inner classes ─────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentTrendPoint {
        private LocalDate date;
        private BigDecimal amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentMethodBreakdown {
        private String method;
        private long count;
        private BigDecimal total;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WriteOffSummary {
        private long cancelledCount;
        private BigDecimal cancelledTotal;
    }

    // ── MVP 21: Bed/Ward occupancy inner classes
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BedOccupancy {
        private long totalBeds;
        private long occupiedBeds;
        private long availableBeds;
        private long reservedBeds;
        private long maintenanceBeds;
        private BigDecimal occupancyRate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WardOccupancyRow {
        private String wardId;
        private String wardName;
        private String wardType;
        private long totalBeds;
        private long occupiedBeds;
        private long availableBeds;
        private BigDecimal occupancyRate;
    }
}
