package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.BedStatus;
import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.StaffShiftStatus;
import com.example.hms.enums.WardType;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Consultation;
import com.example.hms.model.StaffShift;
import com.example.hms.payload.dto.dashboard.HospitalAdminSummaryDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.BedRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.PaymentTransactionRepository;
import com.example.hms.repository.StaffAvailabilityRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.StaffShiftRepository;
import com.example.hms.repository.platform.HospitalPlatformServiceLinkRepository;
import com.example.hms.service.HospitalAdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalAdminDashboardServiceImpl implements HospitalAdminDashboardService {

    private final AppointmentRepository appointmentRepository;
    private final AdmissionRepository admissionRepository;
    private final ConsultationRepository consultationRepository;
    private final BedRepository bedRepository;
    private final BillingInvoiceRepository billingInvoiceRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final StaffRepository staffRepository;
    private final StaffAvailabilityRepository staffAvailabilityRepository;
    private final AuditEventLogRepository auditEventLogRepository;
    private final StaffShiftRepository staffShiftRepository;
    private final HospitalPlatformServiceLinkRepository hospitalPlatformServiceLinkRepository;

    @Override
    public HospitalAdminSummaryDTO getSummary(UUID hospitalId, LocalDate asOfDate, int auditLimit) {
        return HospitalAdminSummaryDTO.builder()
            .hospitalId(hospitalId)
            .asOfDate(asOfDate)
            .appointments(buildAppointmentMetrics(hospitalId, asOfDate))
            .admissions(buildAdmissionMetrics(hospitalId, asOfDate))
            .consultations(buildConsultationMetrics(hospitalId))
            .staffing(buildStaffingMetrics(hospitalId, asOfDate))
            .billing(buildBillingMetrics(hospitalId))
            .recentAuditEvents(buildAuditSnippets(hospitalId, auditLimit))
            .staffingByDepartment(buildStaffingByDepartment(hospitalId, asOfDate))
            .consultBacklog(buildConsultBacklog(hospitalId))
            .auditTrend(buildAuditTrend(hospitalId))
            .invoiceAging(buildInvoiceAging(hospitalId))
            .integrations(buildIntegrationStatus(hospitalId))
            .paymentCollectionRate(buildPaymentCollectionRate(hospitalId))
            .licenseAlerts(buildLicenseAlerts(hospitalId))
            .leave(buildLeaveMetrics(hospitalId, asOfDate))
            .paymentTrend(buildPaymentTrend(hospitalId, asOfDate))
            .paymentMethodBreakdown(buildPaymentMethodBreakdown(hospitalId, asOfDate))
            .writeOffs(buildWriteOffs(hospitalId))
            .bedOccupancy(buildBedOccupancy(hospitalId))
            .wardOccupancy(buildWardOccupancy(hospitalId))
            .build();
    }

    private HospitalAdminSummaryDTO.AppointmentMetrics buildAppointmentMetrics(UUID hospitalId, LocalDate date) {
        var appts = appointmentRepository.findByHospital_IdAndAppointmentDate(hospitalId, date);
        long total = appts.size();
        long completed = appts.stream().filter(a -> a.getStatus() == AppointmentStatus.COMPLETED).count();
        long noShows = appts.stream().filter(a -> a.getStatus() == AppointmentStatus.NO_SHOW).count();
        long cancelled = appts.stream().filter(a -> a.getStatus() == AppointmentStatus.CANCELLED).count();
        long pending = appts.stream().filter(a ->
            a.getStatus() == AppointmentStatus.PENDING
            || a.getStatus() == AppointmentStatus.SCHEDULED
            || a.getStatus() == AppointmentStatus.CONFIRMED).count();
        long inProgress = appts.stream().filter(a -> a.getStatus() == AppointmentStatus.IN_PROGRESS).count();

        return HospitalAdminSummaryDTO.AppointmentMetrics.builder()
            .today(total).completed(completed).noShows(noShows)
            .cancelled(cancelled).pending(pending).inProgress(inProgress)
            .build();
    }

    private HospitalAdminSummaryDTO.AdmissionMetrics buildAdmissionMetrics(UUID hospitalId, LocalDate date) {
        long active = admissionRepository.countActiveAdmissionsByHospital(hospitalId);

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.atTime(LocalTime.MAX);
        long admittedToday = admissionRepository.countAdmissionsByHospitalAndDateRange(hospitalId, dayStart, dayEnd);

        var discharged = admissionRepository.findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(
            hospitalId, AdmissionStatus.DISCHARGED);
        long dischargedToday = discharged.stream()
            .filter(a -> a.getActualDischargeDateTime() != null
                && a.getActualDischargeDateTime().toLocalDate().equals(date))
            .count();

        long awaitingDischarge = admissionRepository.findAwaitingDischarge(hospitalId).size();

        return HospitalAdminSummaryDTO.AdmissionMetrics.builder()
            .active(active).admittedToday(admittedToday)
            .dischargedToday(dischargedToday).awaitingDischarge(awaitingDischarge)
            .build();
    }

    private HospitalAdminSummaryDTO.ConsultationMetrics buildConsultationMetrics(UUID hospitalId) {
        var requested = consultationRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(
            hospitalId, ConsultationStatus.REQUESTED);
        var acknowledged = consultationRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(
            hospitalId, ConsultationStatus.ACKNOWLEDGED);
        var inProgress = consultationRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(
            hospitalId, ConsultationStatus.IN_PROGRESS);

        var completedStatuses = List.of(
            ConsultationStatus.COMPLETED, ConsultationStatus.CANCELLED, ConsultationStatus.DECLINED);
        long overdue = consultationRepository.findOverdueConsultations(LocalDateTime.now(), completedStatuses)
            .stream().filter(c -> c.getHospital().getId().equals(hospitalId)).count();

        return HospitalAdminSummaryDTO.ConsultationMetrics.builder()
            .requested(requested.size())
            .acknowledged(acknowledged.size())
            .inProgress(inProgress.size())
            .overdue(overdue)
            .build();
    }

    private HospitalAdminSummaryDTO.StaffingMetrics buildStaffingMetrics(UUID hospitalId, LocalDate date) {
        long activeStaff = staffRepository.findByHospitalIdAndActiveTrueExcludingDeletedUsers(
            hospitalId, PageRequest.of(0, 1)).getTotalElements();

        var availability = staffAvailabilityRepository.findAll();
        long onShift = availability.stream()
            .filter(sa -> sa.getHospital() != null && sa.getHospital().getId().equals(hospitalId))
            .filter(sa -> sa.getDate() != null && sa.getDate().equals(date))
            .filter(sa -> !sa.isDayOff())
            .filter(sa -> sa.isActive())
            .count();

        long onLeave = staffAvailabilityRepository.findOnLeaveByHospitalAndDate(hospitalId, date).size();
        long upcomingLeave = staffAvailabilityRepository.findOnLeaveByHospitalAndDateRange(
            hospitalId, date.plusDays(1), date.plusDays(7)).size();

        return HospitalAdminSummaryDTO.StaffingMetrics.builder()
            .activeStaff(activeStaff)
            .onShiftToday(onShift)
            .staffOnLeaveToday(onLeave)
            .upcomingLeave(upcomingLeave)
            .build();
    }

    private HospitalAdminSummaryDTO.BillingMetrics buildBillingMetrics(UUID hospitalId) {
        var overdueStatuses = List.of(InvoiceStatus.SENT, InvoiceStatus.PARTIALLY_PAID);
        var overdueInvoices = billingInvoiceRepository.findOverdue(LocalDate.now(), overdueStatuses);
        long hospitalOverdue = overdueInvoices.stream()
            .filter(inv -> inv.getHospital().getId().equals(hospitalId))
            .count();

        // Open balance = sum(totalAmount - amountPaid) for non-PAID, non-CANCELLED invoices at this hospital
        var allHospitalInvoices = billingInvoiceRepository.findByHospital_Id(
            hospitalId, PageRequest.of(0, Integer.MAX_VALUE));
        BigDecimal openBalance = allHospitalInvoices.getContent().stream()
            .filter(inv -> inv.getStatus() != InvoiceStatus.PAID && inv.getStatus() != InvoiceStatus.CANCELLED)
            .map(inv -> inv.getTotalAmount().subtract(inv.getAmountPaid()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return HospitalAdminSummaryDTO.BillingMetrics.builder()
            .overdueInvoices(hospitalOverdue)
            .openBalanceTotal(openBalance)
            .build();
    }

    private List<HospitalAdminSummaryDTO.AuditSnippet> buildAuditSnippets(UUID hospitalId, int limit) {
        var page = auditEventLogRepository.findByAssignment_Hospital_IdOrderByEventTimestampDesc(
            hospitalId, PageRequest.of(0, Math.max(limit, 1)));
        return page.getContent().stream().map(this::toSnippet).toList();
    }

    private HospitalAdminSummaryDTO.AuditSnippet toSnippet(AuditEventLog evt) {
        return HospitalAdminSummaryDTO.AuditSnippet.builder()
            .id(evt.getId().toString())
            .eventType(evt.getEventType() != null ? evt.getEventType().name() : null)
            .status(evt.getStatus() != null ? evt.getStatus().name() : null)
            .entityType(evt.getEntityType())
            .resourceName(evt.getResourceName())
            .userName(evt.getUserName())
            .eventTimestamp(evt.getEventTimestamp())
            .build();
    }

    // ── MVP 17: Staffing by department ──────────────────────────

    private List<HospitalAdminSummaryDTO.DepartmentStaffingRow> buildStaffingByDepartment(UUID hospitalId, LocalDate date) {
        List<StaffShift> shifts = staffShiftRepository.findByHospital_IdAndShiftDate(hospitalId, date);

        Map<String, List<StaffShift>> byDept = shifts.stream()
            .collect(Collectors.groupingBy(s ->
                s.getDepartment() != null ? s.getDepartment().getId().toString() : "unassigned"));

        return byDept.entrySet().stream().map(e -> {
            List<StaffShift> deptShifts = e.getValue();
            String deptName = deptShifts.stream()
                .filter(s -> s.getDepartment() != null)
                .findFirst()
                .map(s -> s.getDepartment().getName())
                .orElse("Unassigned");

            long scheduled = deptShifts.stream()
                .filter(s -> s.getStatus() != StaffShiftStatus.CANCELLED).count();
            long cancelled = deptShifts.stream()
                .filter(s -> s.getStatus() == StaffShiftStatus.CANCELLED).count();
            long active = deptShifts.stream()
                .filter(s -> s.getStatus() == StaffShiftStatus.SCHEDULED).count();

            return HospitalAdminSummaryDTO.DepartmentStaffingRow.builder()
                .departmentId(e.getKey())
                .departmentName(deptName)
                .scheduledShifts(scheduled)
                .cancelledShifts(cancelled)
                .activeStaff(active)
                .build();
        }).toList();
    }

    // ── MVP 17: Consultation backlog ────────────────────────────

    private List<HospitalAdminSummaryDTO.ConsultBacklogItem> buildConsultBacklog(UUID hospitalId) {
        var pendingStatuses = List.of(
            ConsultationStatus.REQUESTED, ConsultationStatus.ACKNOWLEDGED,
            ConsultationStatus.ASSIGNED, ConsultationStatus.SCHEDULED, ConsultationStatus.IN_PROGRESS);
        List<Consultation> backlog = consultationRepository.findByHospitalAndStatuses(hospitalId, pendingStatuses);

        LocalDateTime now = LocalDateTime.now();
        return backlog.stream().map(c -> HospitalAdminSummaryDTO.ConsultBacklogItem.builder()
            .consultationId(c.getId().toString())
            .patientName(c.getPatient() != null
                ? c.getPatient().getFirstName() + " " + c.getPatient().getLastName() : "Unknown")
            .specialtyRequested(c.getSpecialtyRequested())
            .urgency(c.getUrgency() != null ? c.getUrgency().name() : null)
            .status(c.getStatus().name())
            .requestedAt(c.getRequestedAt())
            .slaDueBy(c.getSlaDueBy())
            .overdue(c.getSlaDueBy() != null && c.getSlaDueBy().isBefore(now))
            .build()
        ).toList();
    }

    // ── MVP 17: Audit trend (last 7 days) ───────────────────────

    private List<HospitalAdminSummaryDTO.AuditDayCount> buildAuditTrend(UUID hospitalId) {
        LocalDateTime sevenDaysAgo = LocalDate.now().minusDays(7).atStartOfDay();
        List<Object[]> rows = auditEventLogRepository.countDailyByHospital(hospitalId, sevenDaysAgo);
        return rows.stream().map(r -> HospitalAdminSummaryDTO.AuditDayCount.builder()
            .date(((java.sql.Date) r[0]).toLocalDate())
            .count((Long) r[1])
            .build()
        ).toList();
    }

    // ── MVP 18: Invoice aging buckets ───────────────────────────

    private HospitalAdminSummaryDTO.InvoiceAgingBuckets buildInvoiceAging(UUID hospitalId) {
        var openStatuses = List.of(InvoiceStatus.DRAFT, InvoiceStatus.SENT, InvoiceStatus.PARTIALLY_PAID);
        var allInvoices = billingInvoiceRepository.findByHospital_Id(hospitalId, PageRequest.of(0, Integer.MAX_VALUE));
        List<BillingInvoice> open = allInvoices.getContent().stream()
            .filter(inv -> openStatuses.contains(inv.getStatus()))
            .toList();

        LocalDate today = LocalDate.now();

        var current = agingBucket("Current", open, inv -> {
            long days = ChronoUnit.DAYS.between(inv.getDueDate(), today);
            return days <= 0;
        });
        var days1to30 = agingBucket("1-30 Days", open, inv -> {
            long days = ChronoUnit.DAYS.between(inv.getDueDate(), today);
            return days >= 1 && days <= 30;
        });
        var days31to60 = agingBucket("31-60 Days", open, inv -> {
            long days = ChronoUnit.DAYS.between(inv.getDueDate(), today);
            return days >= 31 && days <= 60;
        });
        var days61to90 = agingBucket("61-90 Days", open, inv -> {
            long days = ChronoUnit.DAYS.between(inv.getDueDate(), today);
            return days >= 61 && days <= 90;
        });
        var over90 = agingBucket("90+ Days", open, inv -> {
            long days = ChronoUnit.DAYS.between(inv.getDueDate(), today);
            return days > 90;
        });

        BigDecimal grandTotal = open.stream()
            .map(inv -> inv.getTotalAmount().subtract(inv.getAmountPaid()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return HospitalAdminSummaryDTO.InvoiceAgingBuckets.builder()
            .current(current).days1to30(days1to30).days31to60(days31to60)
            .days61to90(days61to90).over90(over90).grandTotal(grandTotal)
            .build();
    }

    private HospitalAdminSummaryDTO.InvoiceAgingBucket agingBucket(
            String label, List<BillingInvoice> invoices, java.util.function.Predicate<BillingInvoice> filter) {
        var matching = invoices.stream().filter(filter).toList();
        BigDecimal total = matching.stream()
            .map(inv -> inv.getTotalAmount().subtract(inv.getAmountPaid()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return HospitalAdminSummaryDTO.InvoiceAgingBucket.builder()
            .label(label).count(matching.size()).total(total).build();
    }

    // ── MVP 18: Payment collection rate ─────────────────────────

    private BigDecimal buildPaymentCollectionRate(UUID hospitalId) {
        var allInvoices = billingInvoiceRepository.findByHospital_Id(hospitalId, PageRequest.of(0, Integer.MAX_VALUE));
        var invoices = allInvoices.getContent();
        if (invoices.isEmpty()) return BigDecimal.ZERO;

        BigDecimal totalBilled = invoices.stream()
            .map(BillingInvoice::getTotalAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCollected = invoices.stream()
            .map(BillingInvoice::getAmountPaid)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalBilled.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return totalCollected.multiply(BigDecimal.valueOf(100))
            .divide(totalBilled, 1, RoundingMode.HALF_UP);
    }

    // ── MVP 18: Integration status ──────────────────────────────

    private List<HospitalAdminSummaryDTO.IntegrationStatusRow> buildIntegrationStatus(UUID hospitalId) {
        var links = hospitalPlatformServiceLinkRepository.findByHospitalId(hospitalId);
        return links.stream().map(link -> {
            var svc = link.getOrganizationService();
            return HospitalAdminSummaryDTO.IntegrationStatusRow.builder()
                .serviceType(svc.getServiceType() != null ? svc.getServiceType().name() : "UNKNOWN")
                .provider(svc.getProvider())
                .status(svc.getStatus() != null ? svc.getStatus().name() : "UNKNOWN")
                .enabled(link.isEnabled())
                .baseUrl(link.getOverrideEndpoint() != null ? link.getOverrideEndpoint() : svc.getBaseUrl())
                .build();
        }).toList();
    }
    // ── MVP 20: Payment trend (last 30 days) ────────────────────────────────

    private List<HospitalAdminSummaryDTO.PaymentTrendPoint> buildPaymentTrend(UUID hospitalId, LocalDate asOfDate) {
        LocalDate from = asOfDate.minusDays(29);
        List<Object[]> rows = paymentTransactionRepository.dailyCollectionsByHospital(hospitalId, from, asOfDate);
        return rows.stream().map(r -> HospitalAdminSummaryDTO.PaymentTrendPoint.builder()
            .date(((java.sql.Date) r[0]).toLocalDate())
            .amount((BigDecimal) r[1])
            .build()
        ).toList();
    }

    // ── MVP 20: Payment method breakdown ─────────────────────────────────

    private List<HospitalAdminSummaryDTO.PaymentMethodBreakdown> buildPaymentMethodBreakdown(UUID hospitalId, LocalDate asOfDate) {
        LocalDate from = asOfDate.minusDays(29);
        List<Object[]> rows = paymentTransactionRepository.methodBreakdownByHospital(hospitalId, from, asOfDate);
        return rows.stream().map(r -> HospitalAdminSummaryDTO.PaymentMethodBreakdown.builder()
            .method(((PaymentMethod) r[0]).name())
            .count((Long) r[1])
            .total((BigDecimal) r[2])
            .build()
        ).toList();
    }

    // ── MVP 20: Write-off tracking ──────────────────────────────────────

    private HospitalAdminSummaryDTO.WriteOffSummary buildWriteOffs(UUID hospitalId) {
        var allInvoices = billingInvoiceRepository.findByHospital_Id(hospitalId, PageRequest.of(0, Integer.MAX_VALUE));
        var cancelled = allInvoices.getContent().stream()
            .filter(inv -> inv.getStatus() == InvoiceStatus.CANCELLED)
            .toList();
        BigDecimal cancelledTotal = cancelled.stream()
            .map(inv -> inv.getTotalAmount().subtract(inv.getAmountPaid()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return HospitalAdminSummaryDTO.WriteOffSummary.builder()
            .cancelledCount(cancelled.size())
            .cancelledTotal(cancelledTotal)
            .build();
    }

    // ── MVP 19: License expiry alerts ─────────────────────────────────────

    private List<HospitalAdminSummaryDTO.LicenseExpiryAlert> buildLicenseAlerts(UUID hospitalId) {
        LocalDate today = LocalDate.now();
        LocalDate cutoff90 = today.plusDays(90);
        var staff = staffRepository.findByHospitalIdAndLicenseExpiringBefore(hospitalId, cutoff90);

        return staff.stream().map(s -> {
            long daysUntil = ChronoUnit.DAYS.between(today, s.getLicenseExpiryDate());
            String severity;
            if (daysUntil < 0) {
                severity = "EXPIRED";
            } else if (daysUntil <= 30) {
                severity = "CRITICAL";
            } else {
                severity = "WARNING";
            }
            return HospitalAdminSummaryDTO.LicenseExpiryAlert.builder()
                .staffId(s.getId().toString())
                .staffName(s.getFullName())
                .jobTitle(s.getJobTitle() != null ? s.getJobTitle().name() : null)
                .departmentName(s.getDepartment() != null ? s.getDepartment().getName() : "Unassigned")
                .licenseNumber(s.getLicenseNumber())
                .licenseExpiryDate(s.getLicenseExpiryDate())
                .severity(severity)
                .daysUntilExpiry(daysUntil)
                .build();
        }).sorted((a, b) -> Long.compare(a.getDaysUntilExpiry(), b.getDaysUntilExpiry())).toList();
    }

    // ── MVP 19: Leave metrics ───────────────────────────────────────────

    private HospitalAdminSummaryDTO.LeaveMetrics buildLeaveMetrics(UUID hospitalId, LocalDate date) {
        long onLeaveToday = staffAvailabilityRepository.findOnLeaveByHospitalAndDate(hospitalId, date).size();
        long upcoming = staffAvailabilityRepository.findOnLeaveByHospitalAndDateRange(
            hospitalId, date.plusDays(1), date.plusDays(7)).size();
        return HospitalAdminSummaryDTO.LeaveMetrics.builder()
            .onLeaveToday(onLeaveToday)
            .upcomingLeaveNext7Days(upcoming)
            .build();
    }

    // ── MVP 21: Bed occupancy ──────────────────────────────────────────

    private HospitalAdminSummaryDTO.BedOccupancy buildBedOccupancy(UUID hospitalId) {
        List<Object[]> rows = bedRepository.countByHospitalGroupByStatus(hospitalId);
        Map<BedStatus, Long> counts = new EnumMap<>(BedStatus.class);
        for (Object[] r : rows) {
            counts.put((BedStatus) r[0], (Long) r[1]);
        }
        long occupied  = counts.getOrDefault(BedStatus.OCCUPIED, 0L);
        long available = counts.getOrDefault(BedStatus.AVAILABLE, 0L);
        long reserved  = counts.getOrDefault(BedStatus.RESERVED, 0L);
        long maintenance = counts.getOrDefault(BedStatus.MAINTENANCE, 0L)
                         + counts.getOrDefault(BedStatus.OUT_OF_SERVICE, 0L);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        BigDecimal rate = total > 0
            ? BigDecimal.valueOf(occupied).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        return HospitalAdminSummaryDTO.BedOccupancy.builder()
            .totalBeds(total).occupiedBeds(occupied).availableBeds(available)
            .reservedBeds(reserved).maintenanceBeds(maintenance).occupancyRate(rate)
            .build();
    }

    private List<HospitalAdminSummaryDTO.WardOccupancyRow> buildWardOccupancy(UUID hospitalId) {
        List<Object[]> rows = bedRepository.countByHospitalGroupByWardAndStatus(hospitalId);

        // Group by wardId -> { wardName, wardType, statusCounts }
        Map<UUID, Map<String, Object>> wardMap = new LinkedHashMap<>();
        for (Object[] r : rows) {
            UUID wardId = (UUID) r[0];
            wardMap.computeIfAbsent(wardId, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", (String) r[1]);
                m.put("type", ((WardType) r[2]).name());
                m.put("counts", new EnumMap<>(BedStatus.class));
                return m;
            });
            @SuppressWarnings("unchecked")
            Map<BedStatus, Long> c = (Map<BedStatus, Long>) wardMap.get(wardId).get("counts");
            c.put((BedStatus) r[3], (Long) r[4]);
        }

        List<HospitalAdminSummaryDTO.WardOccupancyRow> result = new ArrayList<>();
        for (var entry : wardMap.entrySet()) {
            UUID wardId = entry.getKey();
            Map<String, Object> data = entry.getValue();
            @SuppressWarnings("unchecked")
            Map<BedStatus, Long> counts = (Map<BedStatus, Long>) data.get("counts");
            long occupied  = counts.getOrDefault(BedStatus.OCCUPIED, 0L);
            long avail     = counts.getOrDefault(BedStatus.AVAILABLE, 0L);
            long total     = counts.values().stream().mapToLong(Long::longValue).sum();
            BigDecimal rate = total > 0
                ? BigDecimal.valueOf(occupied).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            result.add(HospitalAdminSummaryDTO.WardOccupancyRow.builder()
                .wardId(wardId.toString())
                .wardName((String) data.get("name"))
                .wardType((String) data.get("type"))
                .totalBeds(total).occupiedBeds(occupied).availableBeds(avail)
                .occupancyRate(rate)
                .build());
        }
        return result;
    }
}
