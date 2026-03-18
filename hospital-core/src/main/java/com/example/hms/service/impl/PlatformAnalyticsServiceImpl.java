package com.example.hms.service.impl;

import com.example.hms.model.Patient;
import com.example.hms.payload.dto.analytics.PlatformAnalyticsDTO;
import com.example.hms.repository.*;
import com.example.hms.service.PlatformAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformAnalyticsServiceImpl implements PlatformAnalyticsService {

    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final EncounterRepository encounterRepository;
    private final BillingInvoiceRepository billingInvoiceRepository;
    private final LabOrderRepository labOrderRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final UserRepository userRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;

    @Override
    public PlatformAnalyticsDTO getAnalytics(int trendDays) {
        long totalPatients = patientRepository.count();
        long totalEncounters = encounterRepository.count();
        long totalAppointments = appointmentRepository.count();
        long totalInvoices = billingInvoiceRepository.count();
        long totalLabOrders = labOrderRepository.count();
        long totalPrescriptions = prescriptionRepository.count();
        long totalUsers = userRepository.count();
        long activeHospitals = hospitalRepository.countByActiveTrue();

        List<PlatformAnalyticsDTO.TrendPoint> appointmentTrend = buildAppointmentTrend(trendDays);

        Map<String, Long> appointmentsByStatus = buildAppointmentStatusBreakdown();
        Map<String, Long> encountersByStatus = buildEncounterStatusBreakdown();
        Map<String, Long> invoicesByStatus = buildInvoiceStatusBreakdown();

        List<PlatformAnalyticsDTO.DepartmentUtilization> deptUtil = buildDepartmentUtilization();
        List<PlatformAnalyticsDTO.HospitalMetric> hospitalMetrics = buildHospitalMetrics();

        return PlatformAnalyticsDTO.builder()
                .totalPatients(totalPatients)
                .totalEncounters(totalEncounters)
                .totalAppointments(totalAppointments)
                .totalInvoices(totalInvoices)
                .totalLabOrders(totalLabOrders)
                .totalPrescriptions(totalPrescriptions)
                .totalUsers(totalUsers)
                .activeHospitals(activeHospitals)
                .appointmentTrend(appointmentTrend)
                .encounterTrend(Collections.emptyList())
                .patientRegistrationTrend(Collections.emptyList())
                .appointmentsByStatus(appointmentsByStatus)
                .encountersByStatus(encountersByStatus)
                .invoicesByStatus(invoicesByStatus)
                .departmentUtilization(deptUtil)
                .hospitalMetrics(hospitalMetrics)
                .build();
    }

    private List<PlatformAnalyticsDTO.TrendPoint> buildAppointmentTrend(int days) {
        List<PlatformAnalyticsDTO.TrendPoint> trend = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            long count = appointmentRepository.countByAppointmentDateBetween(date, date);
            trend.add(PlatformAnalyticsDTO.TrendPoint.builder().date(date).count(count).build());
        }
        return trend;
    }

    private Map<String, Long> buildAppointmentStatusBreakdown() {
        Map<String, Long> result = new LinkedHashMap<>();
        appointmentRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        a -> a.getStatus() != null ? a.getStatus().name() : "UNKNOWN",
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private Map<String, Long> buildEncounterStatusBreakdown() {
        Map<String, Long> result = new LinkedHashMap<>();
        encounterRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        e -> e.getStatus() != null ? e.getStatus().name() : "UNKNOWN",
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private Map<String, Long> buildInvoiceStatusBreakdown() {
        Map<String, Long> result = new LinkedHashMap<>();
        billingInvoiceRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        inv -> inv.getStatus() != null ? inv.getStatus().name() : "UNKNOWN",
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private List<PlatformAnalyticsDTO.DepartmentUtilization> buildDepartmentUtilization() {
        // Preload all appointments once and group by department ID to avoid N+1
        Map<UUID, Long> countsByDept = appointmentRepository.findAll().stream()
                .filter(a -> a.getDepartment() != null)
                .collect(Collectors.groupingBy(a -> a.getDepartment().getId(), Collectors.counting()));

        return departmentRepository.findAll().stream()
                .map(dept -> PlatformAnalyticsDTO.DepartmentUtilization.builder()
                        .departmentName(dept.getName())
                        .appointmentCount(countsByDept.getOrDefault(dept.getId(), 0L))
                        .encounterCount(0)
                        .build())
                .sorted(Comparator.comparingLong(PlatformAnalyticsDTO.DepartmentUtilization::getAppointmentCount).reversed())
                .limit(10)
                .toList();
    }

    private List<PlatformAnalyticsDTO.HospitalMetric> buildHospitalMetrics() {
        // Preload all patients and appointments once to avoid N+1
        Map<UUID, Long> patientsByHospital = patientRepository.findAll().stream()
                .filter(p -> p.getHospitalId() != null)
                .collect(Collectors.groupingBy(Patient::getHospitalId, Collectors.counting()));
        Map<UUID, Long> appointmentsByHospital = appointmentRepository.findAll().stream()
                .filter(a -> a.getHospital() != null)
                .collect(Collectors.groupingBy(a -> a.getHospital().getId(), Collectors.counting()));

        return hospitalRepository.findAll().stream()
                .filter(h -> h.isActive())
                .map(h -> PlatformAnalyticsDTO.HospitalMetric.builder()
                        .hospitalName(h.getName())
                        .patientCount(patientsByHospital.getOrDefault(h.getId(), 0L))
                        .appointmentCount(appointmentsByHospital.getOrDefault(h.getId(), 0L))
                        .staffCount(0)
                        .build())
                .sorted(Comparator.comparingLong(PlatformAnalyticsDTO.HospitalMetric::getPatientCount).reversed())
                .toList();
    }
}
