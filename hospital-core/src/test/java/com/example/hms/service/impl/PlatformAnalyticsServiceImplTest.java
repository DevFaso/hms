package com.example.hms.service.impl;

import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.analytics.PlatformAnalyticsDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PlatformCountRows;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA CX-002 / CX-003: the service reads the grouped counts of its repositories
 * (their SQL runs on PostgreSQL in {@code PlatformAnalyticsCountsPostgresIT};
 * the anchors of QA D791 are left out there, by the query).
 */
@ExtendWith(MockitoExtension.class)
class PlatformAnalyticsServiceImplTest {

    @Mock private PatientRepository patientRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private BillingInvoiceRepository billingInvoiceRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private UserRepository userRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private StaffRepository staffRepository;

    @InjectMocks
    private PlatformAnalyticsServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static PlatformCountRows.IdCount idCount(UUID id, long total) {
        return new PlatformCountRows.IdCount() {
            @Override public UUID getOwnerId() { return id; }
            @Override public long getTotal() { return total; }
        };
    }

    private static PlatformCountRows.StatusCount statusCount(String label, long total) {
        return new PlatformCountRows.StatusCount() {
            @Override public String getLabel() { return label; }
            @Override public long getTotal() { return total; }
        };
    }

    private static PlatformCountRows.DayCount dayCount(LocalDate day, long total) {
        return new PlatformCountRows.DayCount() {
            @Override public LocalDate getDay() { return day; }
            @Override public long getTotal() { return total; }
        };
    }

    private Department deptWith(UUID id, String name, String hospitalName) {
        Department d = mock(Department.class);
        when(d.getId()).thenReturn(id);
        when(d.getName()).thenReturn(name);
        Hospital h = mock(Hospital.class);
        when(h.getName()).thenReturn(hospitalName);
        when(d.getHospital()).thenReturn(h);
        return d;
    }

    private Hospital hospitalWith(UUID id, String name, boolean active) {
        Hospital h = mock(Hospital.class);
        lenient().when(h.getId()).thenReturn(id);
        lenient().when(h.getName()).thenReturn(name);
        lenient().when(h.isActive()).thenReturn(active);
        return h;
    }

    // ── getAnalytics ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAnalytics()")
    class GetAnalytics {

        @Test
        @DisplayName("returns correct aggregate counts from repositories")
        void returnsAggregateCounts() {
            when(patientRepository.count()).thenReturn(100L);
            when(encounterRepository.countVisits()).thenReturn(200L);
            when(appointmentRepository.count()).thenReturn(300L);
            when(billingInvoiceRepository.count()).thenReturn(50L);
            when(labOrderRepository.count()).thenReturn(75L);
            when(prescriptionRepository.count()).thenReturn(60L);
            when(userRepository.count()).thenReturn(40L);
            when(hospitalRepository.countActiveHospitals()).thenReturn(3L);

            PlatformAnalyticsDTO result = service.getAnalytics(7);

            assertThat(result.getTotalPatients()).isEqualTo(100L);
            assertThat(result.getTotalEncounters()).isEqualTo(200L);
            assertThat(result.getTotalAppointments()).isEqualTo(300L);
            assertThat(result.getTotalInvoices()).isEqualTo(50L);
            assertThat(result.getTotalLabOrders()).isEqualTo(75L);
            assertThat(result.getTotalPrescriptions()).isEqualTo(60L);
            assertThat(result.getTotalUsers()).isEqualTo(40L);
            assertThat(result.getActiveHospitals()).isEqualTo(3L);
        }

        @Test
        @DisplayName("builds every trend for the specified days, a day with no row a true zero")
        void buildsTrends() {
            LocalDate today = LocalDate.now();
            when(appointmentRepository.countPerDay(today.minusDays(2), today))
                .thenReturn(List.of(dayCount(today, 5L), dayCount(today.minusDays(2), 1L)));
            when(encounterRepository.countVisitsPerDay(any(), any())).thenReturn(List.of(dayCount(today, 4L)));
            when(patientRepository.countRegistrationsPerDay(any(), any()))
                .thenReturn(List.of(dayCount(today.minusDays(1), 2L)));

            PlatformAnalyticsDTO result = service.getAnalytics(3);

            assertThat(result.getAppointmentTrend()).extracting(PlatformAnalyticsDTO.TrendPoint::getDate)
                .containsExactly(today.minusDays(2), today.minusDays(1), today);
            assertThat(result.getAppointmentTrend()).extracting(PlatformAnalyticsDTO.TrendPoint::getCount)
                .containsExactly(1L, 0L, 5L);
            assertThat(result.getEncounterTrend()).extracting(PlatformAnalyticsDTO.TrendPoint::getCount)
                .containsExactly(0L, 0L, 4L);
            assertThat(result.getPatientRegistrationTrend()).extracting(PlatformAnalyticsDTO.TrendPoint::getCount)
                .containsExactly(0L, 2L, 0L);
        }

        @Test
        @DisplayName("groups appointments by status with null-safe UNKNOWN fallback, largest first")
        void groupsAppointmentsByStatus() {
            when(appointmentRepository.countPerStatus())
                .thenReturn(List.of(statusCount("SCHEDULED", 1L), statusCount(null, 2L)));

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getAppointmentsByStatus()).containsEntry("SCHEDULED", 1L);
            assertThat(result.getAppointmentsByStatus()).containsEntry("UNKNOWN", 2L);
            assertThat(result.getAppointmentsByStatus().keySet()).containsExactly("UNKNOWN", "SCHEDULED");
        }

        @Test
        @DisplayName("groups encounters by status with null-safe UNKNOWN fallback, by the visits query")
        void groupsEncountersByStatus() {
            when(encounterRepository.countVisitsPerStatus())
                .thenReturn(List.of(statusCount("IN_PROGRESS", 1L), statusCount(null, 1L)));

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getEncountersByStatus()).containsEntry("IN_PROGRESS", 1L);
            assertThat(result.getEncountersByStatus()).containsEntry("UNKNOWN", 1L);
        }

        @Test
        @DisplayName("groups invoices by status with null-safe UNKNOWN fallback")
        void groupsInvoicesByStatus() {
            when(billingInvoiceRepository.countPerStatus())
                .thenReturn(List.of(statusCount("PAID", 1L), statusCount(null, 1L)));

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getInvoicesByStatus()).containsEntry("PAID", 1L);
            assertThat(result.getInvoicesByStatus()).containsEntry("UNKNOWN", 1L);
        }

        @Test
        @DisplayName("QA CX-002: department utilization carries real encounter counts and its hospital")
        void buildsDepartmentUtilization() {
            UUID deptId = UUID.randomUUID();
            Department dept = deptWith(deptId, "Cardiology", "Central");
            when(appointmentRepository.countPerDepartment()).thenReturn(List.of(idCount(deptId, 2L)));
            when(encounterRepository.countVisitsPerDepartment()).thenReturn(List.of(idCount(deptId, 95L)));
            when(departmentRepository.findAllForAnalytics()).thenReturn(List.of(dept));

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getDepartmentUtilization()).hasSize(1);
            PlatformAnalyticsDTO.DepartmentUtilization row = result.getDepartmentUtilization().get(0);
            assertThat(row.getDepartmentId()).isEqualTo(deptId);
            assertThat(row.getDepartmentName()).isEqualTo("Cardiology");
            assertThat(row.getHospitalName()).isEqualTo("Central");
            assertThat(row.getAppointmentCount()).isEqualTo(2L);
            assertThat(row.getEncounterCount()).isEqualTo(95L);
        }

        @Test
        @DisplayName("QA CX-003: every department is listed, busiest first, none cut")
        void everyDepartmentIsListed() {
            List<Department> all = new ArrayList<>();
            List<PlatformCountRows.IdCount> counts = new ArrayList<>();
            for (int i = 0; i < 14; i++) {
                UUID id = UUID.randomUUID();
                all.add(deptWith(id, "Dept " + i, "Central"));
                counts.add(idCount(id, i));
            }
            when(appointmentRepository.countPerDepartment()).thenReturn(counts);
            when(departmentRepository.findAllForAnalytics()).thenReturn(all);

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getDepartmentUtilization()).hasSize(14);
            assertThat(result.getDepartmentUtilization().get(0).getDepartmentName()).isEqualTo("Dept 13");
            assertThat(result.getDepartmentUtilization().get(13).getDepartmentName()).isEqualTo("Dept 0");
        }

        @Test
        @DisplayName("QA CX-002: builds hospital metrics with real staff — filters inactive, sorts by patient count desc")
        void buildsHospitalMetrics() {
            UUID h1Id = UUID.randomUUID();
            UUID h2Id = UUID.randomUUID();
            UUID h3Id = UUID.randomUUID();
            Hospital h1 = hospitalWith(h1Id, "Central Hospital", true);
            Hospital h2 = hospitalWith(h2Id, "Closed Hospital", false);
            Hospital h3 = hospitalWith(h3Id, "Small Hospital", true);
            when(hospitalRepository.findAllHospitals()).thenReturn(List.of(h3, h1, h2));
            when(patientRepository.countPerHospital()).thenReturn(List.of(idCount(h1Id, 2L)));
            when(appointmentRepository.countPerHospital()).thenReturn(List.of(idCount(h1Id, 1L)));
            when(staffRepository.countPerHospitalExcludingDeletedUsers())
                .thenReturn(List.of(idCount(h1Id, 14L), idCount(h3Id, 8L)));

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getHospitalMetrics()).hasSize(2); // h2 is inactive
            PlatformAnalyticsDTO.HospitalMetric central = result.getHospitalMetrics().get(0);
            assertThat(central.getHospitalId()).isEqualTo(h1Id);
            assertThat(central.getHospitalName()).isEqualTo("Central Hospital");
            assertThat(central.getPatientCount()).isEqualTo(2L);
            assertThat(central.getAppointmentCount()).isEqualTo(1L);
            assertThat(central.getStaffCount()).isEqualTo(14L);
            assertThat(result.getHospitalMetrics().get(1).getStaffCount()).isEqualTo(8L);
        }

        @Test
        @DisplayName("QA CX-002: counting never loads the records")
        void neverLoadsTheRecordsToCountThem() {
            service.getAnalytics(7);

            verify(appointmentRepository, never()).findAll();
            verify(encounterRepository, never()).findAll();
            verify(billingInvoiceRepository, never()).findAll();
            verify(patientRepository, never()).findAll();
            verify(appointmentRepository, never()).countByAppointmentDateBetween(any(), any());
        }

        @Test
        @DisplayName("handles all empty repositories gracefully")
        void handlesEmptyRepos() {
            PlatformAnalyticsDTO result = service.getAnalytics(7);

            assertThat(result.getTotalPatients()).isZero();
            assertThat(result.getAppointmentsByStatus()).isEmpty();
            assertThat(result.getEncountersByStatus()).isEmpty();
            assertThat(result.getInvoicesByStatus()).isEmpty();
            assertThat(result.getDepartmentUtilization()).isEmpty();
            assertThat(result.getHospitalMetrics()).isEmpty();
            assertThat(result.getAppointmentTrend()).hasSize(7);
            assertThat(result.getEncounterTrend()).hasSize(7);
            assertThat(result.getPatientRegistrationTrend()).hasSize(7);
        }
    }
}
