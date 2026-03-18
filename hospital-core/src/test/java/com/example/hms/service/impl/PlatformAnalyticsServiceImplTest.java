package com.example.hms.service.impl;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.model.Appointment;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.analytics.PlatformAnalyticsDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @InjectMocks
    private PlatformAnalyticsServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Appointment appointmentWith(AppointmentStatus status, Department dept, Hospital hospital) {
        Appointment a = mock(Appointment.class);
        when(a.getStatus()).thenReturn(status);
        when(a.getDepartment()).thenReturn(dept);
        when(a.getHospital()).thenReturn(hospital);
        return a;
    }

    private Department deptWith(UUID id, String name) {
        Department d = mock(Department.class);
        when(d.getId()).thenReturn(id);
        when(d.getName()).thenReturn(name);
        return d;
    }

    private Hospital hospitalWith(UUID id, String name, boolean active) {
        Hospital h = mock(Hospital.class);
        lenient().when(h.getId()).thenReturn(id);
        lenient().when(h.getName()).thenReturn(name);
        lenient().when(h.isActive()).thenReturn(active);
        return h;
    }

    private Patient patientWithHospital(UUID hospitalId) {
        Patient p = mock(Patient.class);
        when(p.getHospitalId()).thenReturn(hospitalId);
        return p;
    }

    // ── getAnalytics ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAnalytics()")
    class GetAnalytics {

        @Test
        @DisplayName("returns correct aggregate counts from repositories")
        void returnsAggregateCounts() {
            when(patientRepository.count()).thenReturn(100L);
            when(encounterRepository.count()).thenReturn(200L);
            when(appointmentRepository.count()).thenReturn(300L);
            when(billingInvoiceRepository.count()).thenReturn(50L);
            when(labOrderRepository.count()).thenReturn(75L);
            when(prescriptionRepository.count()).thenReturn(60L);
            when(userRepository.count()).thenReturn(40L);
            when(hospitalRepository.countByActiveTrue()).thenReturn(3L);
            when(appointmentRepository.countByAppointmentDateBetween(any(), any())).thenReturn(0L);
            when(appointmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(encounterRepository.findAll()).thenReturn(Collections.emptyList());
            when(billingInvoiceRepository.findAll()).thenReturn(Collections.emptyList());
            when(departmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(hospitalRepository.findAll()).thenReturn(Collections.emptyList());
            when(patientRepository.findAll()).thenReturn(Collections.emptyList());

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
        @DisplayName("builds appointment trend for specified days")
        void buildsAppointmentTrend() {
            stubCountsToZero();
            when(appointmentRepository.countByAppointmentDateBetween(any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(5L);
            when(appointmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(encounterRepository.findAll()).thenReturn(Collections.emptyList());
            when(billingInvoiceRepository.findAll()).thenReturn(Collections.emptyList());
            when(departmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(hospitalRepository.findAll()).thenReturn(Collections.emptyList());
            when(patientRepository.findAll()).thenReturn(Collections.emptyList());

            PlatformAnalyticsDTO result = service.getAnalytics(3);

            assertThat(result.getAppointmentTrend()).hasSize(3);
            assertThat(result.getAppointmentTrend()).allSatisfy(tp ->
                    assertThat(tp.getCount()).isEqualTo(5L));
        }

        @Test
        @DisplayName("groups appointments by status with null-safe UNKNOWN fallback")
        void groupsAppointmentsByStatus() {
            stubCountsToZero();
            Appointment withStatus = mock(Appointment.class);
            when(withStatus.getStatus()).thenReturn(AppointmentStatus.SCHEDULED);
            Appointment withNull = mock(Appointment.class);
            when(withNull.getStatus()).thenReturn(null);

            when(appointmentRepository.findAll()).thenReturn(List.of(withStatus, withNull));
            when(appointmentRepository.countByAppointmentDateBetween(any(), any())).thenReturn(0L);
            when(encounterRepository.findAll()).thenReturn(Collections.emptyList());
            when(billingInvoiceRepository.findAll()).thenReturn(Collections.emptyList());
            when(departmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(hospitalRepository.findAll()).thenReturn(Collections.emptyList());
            when(patientRepository.findAll()).thenReturn(Collections.emptyList());

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getAppointmentsByStatus()).containsEntry("SCHEDULED", 1L);
            assertThat(result.getAppointmentsByStatus()).containsEntry("UNKNOWN", 1L);
        }

        @Test
        @DisplayName("groups encounters by status with null-safe UNKNOWN fallback")
        void groupsEncountersByStatus() {
            stubCountsToZero();
            Encounter enc = mock(Encounter.class);
            when(enc.getStatus()).thenReturn(EncounterStatus.IN_PROGRESS);
            Encounter encNull = mock(Encounter.class);
            when(encNull.getStatus()).thenReturn(null);

            when(appointmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(appointmentRepository.countByAppointmentDateBetween(any(), any())).thenReturn(0L);
            when(encounterRepository.findAll()).thenReturn(List.of(enc, encNull));
            when(billingInvoiceRepository.findAll()).thenReturn(Collections.emptyList());
            when(departmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(hospitalRepository.findAll()).thenReturn(Collections.emptyList());
            when(patientRepository.findAll()).thenReturn(Collections.emptyList());

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getEncountersByStatus()).containsEntry("IN_PROGRESS", 1L);
            assertThat(result.getEncountersByStatus()).containsEntry("UNKNOWN", 1L);
        }

        @Test
        @DisplayName("groups invoices by status with null-safe UNKNOWN fallback")
        void groupsInvoicesByStatus() {
            stubCountsToZero();
            BillingInvoice inv = mock(BillingInvoice.class);
            when(inv.getStatus()).thenReturn(InvoiceStatus.PAID);
            BillingInvoice invNull = mock(BillingInvoice.class);
            when(invNull.getStatus()).thenReturn(null);

            when(appointmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(appointmentRepository.countByAppointmentDateBetween(any(), any())).thenReturn(0L);
            when(encounterRepository.findAll()).thenReturn(Collections.emptyList());
            when(billingInvoiceRepository.findAll()).thenReturn(List.of(inv, invNull));
            when(departmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(hospitalRepository.findAll()).thenReturn(Collections.emptyList());
            when(patientRepository.findAll()).thenReturn(Collections.emptyList());

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getInvoicesByStatus()).containsEntry("PAID", 1L);
            assertThat(result.getInvoicesByStatus()).containsEntry("UNKNOWN", 1L);
        }

        @Test
        @DisplayName("builds department utilization sorted desc, limited to 10")
        void buildsDepartmentUtilization() {
            stubCountsToZero();
            UUID deptId = UUID.randomUUID();
            Department dept = deptWith(deptId, "Cardiology");

            Appointment a1 = appointmentWith(AppointmentStatus.SCHEDULED, dept, null);
            Appointment a2 = appointmentWith(AppointmentStatus.CONFIRMED, dept, null);
            Appointment noDept = mock(Appointment.class);
            when(noDept.getDepartment()).thenReturn(null);

            when(appointmentRepository.findAll()).thenReturn(List.of(a1, a2, noDept));
            when(appointmentRepository.countByAppointmentDateBetween(any(), any())).thenReturn(0L);
            when(encounterRepository.findAll()).thenReturn(Collections.emptyList());
            when(billingInvoiceRepository.findAll()).thenReturn(Collections.emptyList());
            when(departmentRepository.findAll()).thenReturn(List.of(dept));
            when(hospitalRepository.findAll()).thenReturn(Collections.emptyList());
            when(patientRepository.findAll()).thenReturn(Collections.emptyList());

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getDepartmentUtilization()).hasSize(1);
            assertThat(result.getDepartmentUtilization().get(0).getDepartmentName()).isEqualTo("Cardiology");
            assertThat(result.getDepartmentUtilization().get(0).getAppointmentCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("builds hospital metrics — filters inactive, sorts by patient count desc")
        void buildsHospitalMetrics() {
            stubCountsToZero();
            UUID h1Id = UUID.randomUUID();
            UUID h2Id = UUID.randomUUID();
            Hospital h1 = hospitalWith(h1Id, "Central Hospital", true);
            Hospital h2 = hospitalWith(h2Id, "Closed Hospital", false);

            Patient p1 = patientWithHospital(h1Id);
            Patient p2 = patientWithHospital(h1Id);
            Patient pNull = mock(Patient.class);
            when(pNull.getHospitalId()).thenReturn(null);

            Appointment appt = mock(Appointment.class);
            when(appt.getHospital()).thenReturn(h1);

            when(appointmentRepository.findAll()).thenReturn(List.of(appt));
            when(appointmentRepository.countByAppointmentDateBetween(any(), any())).thenReturn(0L);
            when(encounterRepository.findAll()).thenReturn(Collections.emptyList());
            when(billingInvoiceRepository.findAll()).thenReturn(Collections.emptyList());
            when(departmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(hospitalRepository.findAll()).thenReturn(List.of(h1, h2));
            when(patientRepository.findAll()).thenReturn(List.of(p1, p2, pNull));

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getHospitalMetrics()).hasSize(1); // h2 is inactive
            assertThat(result.getHospitalMetrics().get(0).getHospitalName()).isEqualTo("Central Hospital");
            assertThat(result.getHospitalMetrics().get(0).getPatientCount()).isEqualTo(2L);
            assertThat(result.getHospitalMetrics().get(0).getAppointmentCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("encounter and patient-registration trends are always empty lists")
        void emptyTrends() {
            stubCountsToZero();
            when(appointmentRepository.countByAppointmentDateBetween(any(), any())).thenReturn(0L);
            when(appointmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(encounterRepository.findAll()).thenReturn(Collections.emptyList());
            when(billingInvoiceRepository.findAll()).thenReturn(Collections.emptyList());
            when(departmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(hospitalRepository.findAll()).thenReturn(Collections.emptyList());
            when(patientRepository.findAll()).thenReturn(Collections.emptyList());

            PlatformAnalyticsDTO result = service.getAnalytics(1);

            assertThat(result.getEncounterTrend()).isEmpty();
            assertThat(result.getPatientRegistrationTrend()).isEmpty();
        }

        @Test
        @DisplayName("handles all empty repositories gracefully")
        void handlesEmptyRepos() {
            stubCountsToZero();
            when(appointmentRepository.countByAppointmentDateBetween(any(), any())).thenReturn(0L);
            when(appointmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(encounterRepository.findAll()).thenReturn(Collections.emptyList());
            when(billingInvoiceRepository.findAll()).thenReturn(Collections.emptyList());
            when(departmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(hospitalRepository.findAll()).thenReturn(Collections.emptyList());
            when(patientRepository.findAll()).thenReturn(Collections.emptyList());

            PlatformAnalyticsDTO result = service.getAnalytics(7);

            assertThat(result.getTotalPatients()).isZero();
            assertThat(result.getAppointmentsByStatus()).isEmpty();
            assertThat(result.getEncountersByStatus()).isEmpty();
            assertThat(result.getInvoicesByStatus()).isEmpty();
            assertThat(result.getDepartmentUtilization()).isEmpty();
            assertThat(result.getHospitalMetrics()).isEmpty();
            assertThat(result.getAppointmentTrend()).hasSize(7);
        }
    }

    private void stubCountsToZero() {
        when(patientRepository.count()).thenReturn(0L);
        when(encounterRepository.count()).thenReturn(0L);
        when(appointmentRepository.count()).thenReturn(0L);
        when(billingInvoiceRepository.count()).thenReturn(0L);
        when(labOrderRepository.count()).thenReturn(0L);
        when(prescriptionRepository.count()).thenReturn(0L);
        when(userRepository.count()).thenReturn(0L);
        when(hospitalRepository.countByActiveTrue()).thenReturn(0L);
    }
}
