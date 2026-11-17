package com.example.hms.service.impl;

import com.example.hms.payload.dto.analytics.PlatformAnalyticsDTO;
import com.example.hms.repository.*;
import com.example.hms.service.PlatformAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * QA CX-002 / CX-003 (round 33): every number here is counted by the database
 * (one grouped query per metric, never one per row, never by loading the
 * records), and every department is listed. A metric never answers a literal:
 * {@code staffCount(0)} and {@code encounterCount(0)} told a super-admin that
 * hospitals with 14 staff had none. {@code PlatformAnalyticsHonestNumbersGuardTest}
 * keeps it so.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformAnalyticsServiceImpl implements PlatformAnalyticsService {

    private static final String STATUS_UNKNOWN = "UNKNOWN";

    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final EncounterRepository encounterRepository;
    private final BillingInvoiceRepository billingInvoiceRepository;
    private final LabOrderRepository labOrderRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final UserRepository userRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;

    @Override
    public PlatformAnalyticsDTO getAnalytics(int trendDays) {
        LocalDate lastDay = LocalDate.now();
        LocalDate firstDay = lastDay.minusDays(Math.max(trendDays, 1) - 1L);
        LocalDate afterLast = lastDay.plusDays(1);

        return PlatformAnalyticsDTO.builder()
                .totalPatients(patientRepository.count())
                // QA D791: visits, not the encounters prescriptions anchor themselves on.
                .totalEncounters(encounterRepository.countVisits())
                .totalAppointments(appointmentRepository.count())
                .totalInvoices(billingInvoiceRepository.count())
                .totalLabOrders(labOrderRepository.count())
                .totalPrescriptions(prescriptionRepository.count())
                .totalUsers(userRepository.count())
                .activeHospitals(hospitalRepository.countActiveHospitals())
                .appointmentTrend(trendOf(trendDays, lastDay,
                        appointmentRepository.countPerDay(firstDay, lastDay)))
                .encounterTrend(trendOf(trendDays, lastDay, encounterRepository.countVisitsPerDay(
                        firstDay.atStartOfDay(), afterLast.atStartOfDay())))
                .patientRegistrationTrend(trendOf(trendDays, lastDay, patientRepository.countRegistrationsPerDay(
                        firstDay.atStartOfDay(), afterLast.atStartOfDay())))
                .appointmentsByStatus(statusBreakdown(appointmentRepository.countPerStatus()))
                .encountersByStatus(statusBreakdown(encounterRepository.countVisitsPerStatus()))
                .invoicesByStatus(statusBreakdown(billingInvoiceRepository.countPerStatus()))
                .departmentUtilization(buildDepartmentUtilization())
                .hospitalMetrics(buildHospitalMetrics())
                .build();
    }

    /** One point per day of the window, oldest first; a day with no record is a true zero. */
    private static List<PlatformAnalyticsDTO.TrendPoint> trendOf(
            int days, LocalDate lastDay, List<PlatformCountRows.DayCount> rows) {
        Map<LocalDate, Long> byDay = rows.stream().collect(Collectors.toMap(
                PlatformCountRows.DayCount::getDay, PlatformCountRows.DayCount::getTotal, Long::sum));
        List<PlatformAnalyticsDTO.TrendPoint> trend = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = lastDay.minusDays(i);
            trend.add(PlatformAnalyticsDTO.TrendPoint.builder()
                    .date(date).count(byDay.getOrDefault(date, 0L)).build());
        }
        return trend;
    }

    /** Largest first, a stable order on a tie. */
    private static Map<String, Long> statusBreakdown(List<PlatformCountRows.StatusCount> rows) {
        Map<String, Long> totals = new HashMap<>();
        rows.forEach(row -> totals.merge(
                row.getLabel() != null ? row.getLabel() : STATUS_UNKNOWN, row.getTotal(), Long::sum));
        Map<String, Long> result = new LinkedHashMap<>();
        totals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private static Map<UUID, Long> byOwner(List<PlatformCountRows.IdCount> rows) {
        return rows.stream().collect(Collectors.toMap(
                PlatformCountRows.IdCount::getOwnerId, PlatformCountRows.IdCount::getTotal));
    }

    /** QA CX-003: every department; the table used to stop at ten without saying so. */
    private List<PlatformAnalyticsDTO.DepartmentUtilization> buildDepartmentUtilization() {
        Map<UUID, Long> appointments = byOwner(appointmentRepository.countPerDepartment());
        Map<UUID, Long> visits = byOwner(encounterRepository.countVisitsPerDepartment());

        return departmentRepository.findAllForAnalytics().stream()
                .map(dept -> PlatformAnalyticsDTO.DepartmentUtilization.builder()
                        .departmentId(dept.getId())
                        .departmentName(dept.getName())
                        .hospitalName(dept.getHospital().getName())
                        .appointmentCount(appointments.getOrDefault(dept.getId(), 0L))
                        .encounterCount(visits.getOrDefault(dept.getId(), 0L))
                        .build())
                .sorted(Comparator.comparingLong(PlatformAnalyticsDTO.DepartmentUtilization::getAppointmentCount).reversed()
                        .thenComparing(Comparator.comparingLong(PlatformAnalyticsDTO.DepartmentUtilization::getEncounterCount).reversed())
                        .thenComparing(PlatformAnalyticsDTO.DepartmentUtilization::getHospitalName)
                        .thenComparing(PlatformAnalyticsDTO.DepartmentUtilization::getDepartmentName))
                .toList();
    }

    private List<PlatformAnalyticsDTO.HospitalMetric> buildHospitalMetrics() {
        Map<UUID, Long> patients = byOwner(patientRepository.countPerHospital());
        Map<UUID, Long> appointments = byOwner(appointmentRepository.countPerHospital());
        // QA CX-002: the definition of GET /staff/hospital/{id}, the number a hospital's own page shows.
        Map<UUID, Long> staff = byOwner(staffRepository.countPerHospitalExcludingDeletedUsers());

        return hospitalRepository.findAllHospitals().stream()
                .filter(h -> h.isActive())
                .map(h -> PlatformAnalyticsDTO.HospitalMetric.builder()
                        .hospitalId(h.getId())
                        .hospitalName(h.getName())
                        .patientCount(patients.getOrDefault(h.getId(), 0L))
                        .appointmentCount(appointments.getOrDefault(h.getId(), 0L))
                        .staffCount(staff.getOrDefault(h.getId(), 0L))
                        .build())
                .sorted(Comparator.comparingLong(PlatformAnalyticsDTO.HospitalMetric::getPatientCount).reversed())
                .toList();
    }
}
