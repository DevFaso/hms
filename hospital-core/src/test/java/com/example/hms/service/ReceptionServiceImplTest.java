package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Appointment;
import com.example.hms.model.AppointmentWaitlist;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.DuplicateCandidateDTO;
import com.example.hms.payload.dto.EligibilityAttestationRequestDTO;
import com.example.hms.payload.dto.FlowBoardDTO;
import com.example.hms.payload.dto.FrontDeskPatientSnapshotDTO;
import com.example.hms.payload.dto.InsuranceIssueDTO;
import com.example.hms.payload.dto.ReceptionDashboardSummaryDTO;
import com.example.hms.payload.dto.ReceptionQueueItemDTO;
import com.example.hms.payload.dto.WaitlistEntryRequestDTO;
import com.example.hms.payload.dto.WaitlistEntryResponseDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.AppointmentWaitlistRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceptionServiceImplTest {

    @Mock private AppointmentRepository appointmentRepo;
    @Mock private EncounterRepository encounterRepo;
    @Mock private PatientInsuranceRepository insuranceRepo;
    @Mock private BillingInvoiceRepository invoiceRepo;
    @Mock private PatientRepository patientRepo;
    @Mock private AppointmentWaitlistRepository waitlistRepo;
    @Mock private HospitalRepository hospitalRepo;
    @Mock private DepartmentRepository departmentRepo;
    @Mock private StaffRepository staffRepo;

    @InjectMocks
    private ReceptionServiceImpl service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final LocalDate today = LocalDate.now();

    private Patient patient;
    private Department department;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("John");
        patient.setLastName("Doe");
        patient.setDateOfBirth(LocalDate.of(1985, 3, 15));
        patient.setPhoneNumberPrimary("555-1234");
        patient.setAddress("123 Main St");

        department = mock(Department.class);
        lenient().when(department.getId()).thenReturn(departmentId);
        lenient().when(department.getName()).thenReturn("Cardiology");

        hospital = mock(Hospital.class);
        lenient().when(hospital.getId()).thenReturn(hospitalId);
        lenient().when(hospital.getName()).thenReturn("Central Hospital");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Appointment makeAppointment(AppointmentStatus status) {
        Appointment a = mock(Appointment.class);
        lenient().when(a.getId()).thenReturn(UUID.randomUUID());
        lenient().when(a.getStatus()).thenReturn(status);
        lenient().when(a.getPatient()).thenReturn(patient);
        lenient().when(a.getDepartment()).thenReturn(department);
        lenient().when(a.getStartTime()).thenReturn(LocalTime.of(9, 0));
        lenient().when(a.getStaff()).thenReturn(null);
        return a;
    }

    private Encounter makeEncounter(EncounterStatus status, Appointment appt) {
        Encounter e = mock(Encounter.class);
        lenient().when(e.getId()).thenReturn(UUID.randomUUID());
        lenient().when(e.getStatus()).thenReturn(status);
        lenient().when(e.getAppointment()).thenReturn(appt);
        lenient().when(e.getPatient()).thenReturn(patient);
        lenient().when(e.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(30));
        lenient().when(e.getDepartment()).thenReturn(department);
        return e;
    }

    private Encounter makeWalkInEncounter(EncounterStatus status) {
        Encounter e = mock(Encounter.class);
        lenient().when(e.getId()).thenReturn(UUID.randomUUID());
        lenient().when(e.getStatus()).thenReturn(status);
        lenient().when(e.getAppointment()).thenReturn(null);
        lenient().when(e.getPatient()).thenReturn(patient);
        lenient().when(e.getEncounterDate()).thenReturn(LocalDateTime.now().minusMinutes(15));
        lenient().when(e.getDepartment()).thenReturn(department);
        lenient().when(e.getStaff()).thenReturn(null);
        return e;
    }

    private void stubEmptyInsuranceAndInvoices() {
        lenient().when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(any(), eq(hospitalId)))
                .thenReturn(Collections.emptyList());
        lenient().when(invoiceRepo.findByPatient_IdAndHospital_Id(any(), eq(hospitalId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
    }

    // ── getDashboardSummary ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getDashboardSummary()")
    class GetDashboardSummary {

        @Test
        @DisplayName("returns counts for scheduled, arrived, in-progress, no-show, completed, walk-ins")
        void returnsCounts() {
            Appointment scheduled = makeAppointment(AppointmentStatus.SCHEDULED);
            Appointment noShow = makeAppointment(AppointmentStatus.NO_SHOW);
            Appointment completed = makeAppointment(AppointmentStatus.COMPLETED);

            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(scheduled, noShow, completed));
            when(encounterRepo.findByAppointmentIdIn(any())).thenReturn(Collections.emptyList());

            Encounter walkIn = makeWalkInEncounter(EncounterStatus.ARRIVED);
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(List.of(walkIn));

            ReceptionDashboardSummaryDTO result = service.getDashboardSummary(today, hospitalId);

            assertThat(result.getDate()).isEqualTo(today);
            assertThat(result.getHospitalId()).isEqualTo(hospitalId);
            assertThat(result.getNoShowCount()).isEqualTo(1);
            assertThat(result.getCompletedCount()).isEqualTo(1);
            assertThat(result.getWalkInCount()).isEqualTo(1);
            assertThat(result.getScheduledToday()).isEqualTo(1); // only 'scheduled' qualifies
        }

        @Test
        @DisplayName("handles empty appointment list")
        void handlesEmptyAppointments() {
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(Collections.emptyList());
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(Collections.emptyList());

            ReceptionDashboardSummaryDTO result = service.getDashboardSummary(today, hospitalId);

            assertThat(result.getScheduledToday()).isZero();
            assertThat(result.getArrivedCount()).isZero();
            assertThat(result.getNoShowCount()).isZero();
        }

        @Test
        @DisplayName("counts arrived encounters from linked and walk-in")
        void countsArrivedFromBothSources() {
            Appointment appt = makeAppointment(AppointmentStatus.SCHEDULED);
            Encounter linked = makeEncounter(EncounterStatus.ARRIVED, appt);
            Encounter walkIn = makeWalkInEncounter(EncounterStatus.ARRIVED);

            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(appt));
            when(encounterRepo.findByAppointmentIdIn(any())).thenReturn(List.of(linked));
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(List.of(walkIn));

            ReceptionDashboardSummaryDTO result = service.getDashboardSummary(today, hospitalId);

            assertThat(result.getArrivedCount()).isEqualTo(2);
        }
    }

    // ── getQueue ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getQueue()")
    class GetQueue {

        @Test
        @DisplayName("returns queue items for all appointments")
        void returnsQueueItems() {
            Appointment appt = makeAppointment(AppointmentStatus.SCHEDULED);
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(appt));
            when(encounterRepo.findByAppointmentIdIn(any())).thenReturn(Collections.emptyList());
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(Collections.emptyList());
            stubEmptyInsuranceAndInvoices();

            List<ReceptionQueueItemDTO> result = service.getQueue(today, hospitalId, "ALL", null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPatientName()).isEqualTo("John Doe");
            assertThat(result.get(0).getStatus()).isEqualTo("SCHEDULED");
        }

        @Test
        @DisplayName("filters by status")
        void filtersByStatus() {
            Appointment scheduled = makeAppointment(AppointmentStatus.SCHEDULED);
            Appointment noShow = makeAppointment(AppointmentStatus.NO_SHOW);
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(scheduled, noShow));
            when(encounterRepo.findByAppointmentIdIn(any())).thenReturn(Collections.emptyList());
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(Collections.emptyList());
            stubEmptyInsuranceAndInvoices();

            List<ReceptionQueueItemDTO> result = service.getQueue(today, hospitalId, "NO_SHOW", null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("NO_SHOW");
        }

        @Test
        @DisplayName("filters by department")
        void filtersByDepartment() {
            UUID otherId = UUID.randomUUID();
            Department otherDept = mock(Department.class);
            when(otherDept.getId()).thenReturn(otherId);

            Appointment myDept = makeAppointment(AppointmentStatus.SCHEDULED);
            Appointment otherAppt = makeAppointment(AppointmentStatus.SCHEDULED);
            when(otherAppt.getDepartment()).thenReturn(otherDept);

            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(myDept, otherAppt));
            when(encounterRepo.findByAppointmentIdIn(any())).thenReturn(Collections.emptyList());
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(Collections.emptyList());
            stubEmptyInsuranceAndInvoices();

            List<ReceptionQueueItemDTO> result = service.getQueue(today, hospitalId, "ALL", departmentId, null);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("includes walk-in encounters")
        void includesWalkIns() {
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(Collections.emptyList());
            Encounter walkIn = makeWalkInEncounter(EncounterStatus.ARRIVED);
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(List.of(walkIn));
            stubEmptyInsuranceAndInvoices();

            List<ReceptionQueueItemDTO> result = service.getQueue(today, hospitalId, null, null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getAppointmentReason()).isEqualTo("Walk-in");
        }

        @Test
        @DisplayName("walk-in IN_PROGRESS computed as IN_PROGRESS")
        void walkInInProgressStatus() {
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(Collections.emptyList());
            Encounter walkIn = makeWalkInEncounter(EncounterStatus.IN_PROGRESS);
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(List.of(walkIn));
            stubEmptyInsuranceAndInvoices();

            List<ReceptionQueueItemDTO> result = service.getQueue(today, hospitalId, "ALL", null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("filters by provider")
        void filtersByProvider() {
            UUID providerId = UUID.randomUUID();
            Staff staff = mock(Staff.class);
            when(staff.getId()).thenReturn(providerId);

            Appointment withProvider = makeAppointment(AppointmentStatus.SCHEDULED);
            when(withProvider.getStaff()).thenReturn(staff);
            Appointment noProvider = makeAppointment(AppointmentStatus.SCHEDULED);

            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(withProvider, noProvider));
            when(encounterRepo.findByAppointmentIdIn(any())).thenReturn(Collections.emptyList());
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(Collections.emptyList());
            stubEmptyInsuranceAndInvoices();

            List<ReceptionQueueItemDTO> result = service.getQueue(today, hospitalId, "ALL", null, providerId);

            assertThat(result).hasSize(1);
        }
    }

    // ── getPatientSnapshot ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getPatientSnapshot()")
    class GetPatientSnapshot {

        @Test
        @DisplayName("returns snapshot with billing and insurance info")
        void returnsSnapshot() {
            patient.setHospitalRegistrations(Collections.emptySet());
            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
                    .thenReturn(Collections.emptyList());
            when(invoiceRepo.findByPatient_IdAndHospital_Id(eq(patientId), eq(hospitalId), any()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            FrontDeskPatientSnapshotDTO result = service.getPatientSnapshot(patientId, hospitalId);

            assertThat(result.getFullName()).isEqualTo("John Doe");
            assertThat(result.getPatientId()).isEqualTo(patientId);
            assertThat(result.getAlerts().isMissingInsurance()).isTrue();
        }

        @Test
        @DisplayName("throws when patient not found")
        void throwsWhenNotFound() {
            when(patientRepo.findById(patientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPatientSnapshot(patientId, hospitalId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("detects expired insurance")
        void detectsExpiredInsurance() {
            patient.setHospitalRegistrations(Collections.emptySet());
            PatientInsurance expired = mock(PatientInsurance.class);
            when(expired.getExpirationDate()).thenReturn(LocalDate.now().minusDays(30));
            when(expired.isPrimary()).thenReturn(true);
            when(expired.getId()).thenReturn(UUID.randomUUID());

            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
                    .thenReturn(List.of(expired));
            when(invoiceRepo.findByPatient_IdAndHospital_Id(eq(patientId), eq(hospitalId), any()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            FrontDeskPatientSnapshotDTO result = service.getPatientSnapshot(patientId, hospitalId);

            assertThat(result.getAlerts().isExpiredInsurance()).isTrue();
            assertThat(result.getInsurance().isExpired()).isTrue();
        }

        @Test
        @DisplayName("detects outstanding balance")
        void detectsOutstandingBalance() {
            patient.setHospitalRegistrations(Collections.emptySet());
            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
                    .thenReturn(Collections.emptyList());

            BillingInvoice inv = mock(BillingInvoice.class);
            when(inv.getStatus()).thenReturn(InvoiceStatus.SENT);
            when(inv.getTotalAmount()).thenReturn(new BigDecimal("100.00"));
            when(inv.getAmountPaid()).thenReturn(BigDecimal.ZERO);

            when(invoiceRepo.findByPatient_IdAndHospital_Id(eq(patientId), eq(hospitalId), any()))
                    .thenReturn(new PageImpl<>(List.of(inv)));

            FrontDeskPatientSnapshotDTO result = service.getPatientSnapshot(patientId, hospitalId);

            assertThat(result.getAlerts().isOutstandingBalance()).isTrue();
            assertThat(result.getBilling().getTotalBalanceDue()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("resolves MRN from hospital registration")
        void resolvesMrn() {
            PatientHospitalRegistration reg = mock(PatientHospitalRegistration.class);
            Hospital regHosp = mock(Hospital.class);
            when(regHosp.getId()).thenReturn(hospitalId);
            when(reg.getHospital()).thenReturn(regHosp);
            when(reg.getMrn()).thenReturn("MRN-001");
            patient.setHospitalRegistrations(Set.of(reg));

            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
                    .thenReturn(Collections.emptyList());
            when(invoiceRepo.findByPatient_IdAndHospital_Id(eq(patientId), eq(hospitalId), any()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            FrontDeskPatientSnapshotDTO result = service.getPatientSnapshot(patientId, hospitalId);

            assertThat(result.getMrn()).isEqualTo("MRN-001");
        }

        @Test
        @DisplayName("incomplete demographics when phone or address missing")
        void detectsIncompleteDemographics() {
            patient.setPhoneNumberPrimary(null);
            patient.setHospitalRegistrations(Collections.emptySet());
            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
                    .thenReturn(Collections.emptyList());
            when(invoiceRepo.findByPatient_IdAndHospital_Id(eq(patientId), eq(hospitalId), any()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            FrontDeskPatientSnapshotDTO result = service.getPatientSnapshot(patientId, hospitalId);

            assertThat(result.getAlerts().isIncompleteDemographics()).isTrue();
        }
    }

    // ── getInsuranceIssues ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getInsuranceIssues()")
    class GetInsuranceIssues {

        @Test
        @DisplayName("reports MISSING_INSURANCE for patient with no insurance")
        void reportsMissingInsurance() {
            Appointment appt = makeAppointment(AppointmentStatus.SCHEDULED);
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(appt));
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
                    .thenReturn(Collections.emptyList());

            List<InsuranceIssueDTO> issues = service.getInsuranceIssues(today, hospitalId);

            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).getIssueType()).isEqualTo("MISSING_INSURANCE");
        }

        @Test
        @DisplayName("reports EXPIRED_INSURANCE when all insurances expired")
        void reportsExpiredInsurance() {
            Appointment appt = makeAppointment(AppointmentStatus.SCHEDULED);
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(appt));

            PatientInsurance expired = mock(PatientInsurance.class);
            when(expired.getExpirationDate()).thenReturn(LocalDate.now().minusDays(10));
            when(expired.isPrimary()).thenReturn(true);
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
                    .thenReturn(List.of(expired));

            List<InsuranceIssueDTO> issues = service.getInsuranceIssues(today, hospitalId);

            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).getIssueType()).isEqualTo("EXPIRED_INSURANCE");
        }

        @Test
        @DisplayName("reports NO_PRIMARY when active insurance but no primary")
        void reportsNoPrimary() {
            Appointment appt = makeAppointment(AppointmentStatus.SCHEDULED);
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(appt));

            PatientInsurance active = mock(PatientInsurance.class);
            when(active.getExpirationDate()).thenReturn(null);
            when(active.isPrimary()).thenReturn(false);
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
                    .thenReturn(List.of(active));

            List<InsuranceIssueDTO> issues = service.getInsuranceIssues(today, hospitalId);

            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).getIssueType()).isEqualTo("NO_PRIMARY");
        }

        @Test
        @DisplayName("skips cancelled and no-show appointments")
        void skipsCancelledAndNoShow() {
            Appointment cancelled = makeAppointment(AppointmentStatus.CANCELLED);
            Appointment noShow = makeAppointment(AppointmentStatus.NO_SHOW);
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(cancelled, noShow));

            List<InsuranceIssueDTO> issues = service.getInsuranceIssues(today, hospitalId);

            assertThat(issues).isEmpty();
        }

        @Test
        @DisplayName("no issues when patient has active primary insurance")
        void noIssuesWhenHealthy() {
            Appointment appt = makeAppointment(AppointmentStatus.SCHEDULED);
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(appt));

            PatientInsurance good = mock(PatientInsurance.class);
            when(good.getExpirationDate()).thenReturn(null);
            when(good.isPrimary()).thenReturn(true);
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
                    .thenReturn(List.of(good));

            List<InsuranceIssueDTO> issues = service.getInsuranceIssues(today, hospitalId);

            assertThat(issues).isEmpty();
        }
    }

    // ── getPaymentsPending ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getPaymentsPending()")
    class GetPaymentsPending {

        @Test
        @DisplayName("returns only items with outstanding balance")
        void returnsItemsWithBalance() {
            Appointment appt = makeAppointment(AppointmentStatus.SCHEDULED);
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(appt));
            when(encounterRepo.findByAppointmentIdIn(any())).thenReturn(Collections.emptyList());
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(any(), eq(hospitalId)))
                    .thenReturn(Collections.emptyList());

            BillingInvoice inv = mock(BillingInvoice.class);
            when(inv.getStatus()).thenReturn(InvoiceStatus.SENT);
            when(inv.getTotalAmount()).thenReturn(new BigDecimal("50.00"));
            when(inv.getAmountPaid()).thenReturn(BigDecimal.ZERO);
            when(invoiceRepo.findByPatient_IdAndHospital_Id(any(), eq(hospitalId), any()))
                    .thenReturn(new PageImpl<>(List.of(inv)));

            List<ReceptionQueueItemDTO> result = service.getPaymentsPending(today, hospitalId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isHasOutstandingBalance()).isTrue();
        }
    }

    // ── getFlowBoard ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getFlowBoard()")
    class GetFlowBoard {

        @Test
        @DisplayName("groups items by status into flow board lanes")
        void groupsByStatus() {
            Appointment scheduled = makeAppointment(AppointmentStatus.SCHEDULED);
            Appointment noShow = makeAppointment(AppointmentStatus.NO_SHOW);

            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(scheduled, noShow));
            when(encounterRepo.findByAppointmentIdIn(any())).thenReturn(Collections.emptyList());
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(Collections.emptyList());
            stubEmptyInsuranceAndInvoices();

            FlowBoardDTO result = service.getFlowBoard(today, hospitalId, null);

            assertThat(result.getScheduled()).hasSize(1);
            assertThat(result.getNoShow()).hasSize(1);
        }
    }

    // ── getDuplicateCandidates ───────────────────────────────────────────────

    @Nested
    @DisplayName("getDuplicateCandidates()")
    class GetDuplicateCandidates {

        @Test
        @DisplayName("returns candidates with confidence score >= 40")
        void returnsHighConfidenceCandidates() {
            Patient p = new Patient();
            p.setId(UUID.randomUUID());
            p.setFirstName("John");
            p.setLastName("Doe");
            p.setDateOfBirth(LocalDate.of(1985, 3, 15));
            p.setPhoneNumberPrimary("555-1234");
            p.setHospitalRegistrations(Collections.emptySet());

            when(patientRepo.searchPatientsExtended(any(), any(), any(), any(), any(), eq(hospitalId), eq(true), any()))
                    .thenReturn(new PageImpl<>(List.of(p)));

            List<DuplicateCandidateDTO> result = service.getDuplicateCandidates(
                    "John Doe", "1985-03-15", "555-1234", hospitalId);

            assertThat(result).isNotEmpty();
            assertThat(result.get(0).getConfidenceScore()).isGreaterThanOrEqualTo(40);
        }

        @Test
        @DisplayName("filters out candidates below score 40")
        void filtersLowScore() {
            Patient p = new Patient();
            p.setId(UUID.randomUUID());
            p.setFirstName("Alice");
            p.setLastName("Wonder");
            p.setHospitalRegistrations(Collections.emptySet());

            when(patientRepo.searchPatientsExtended(any(), any(), any(), any(), any(), eq(hospitalId), eq(true), any()))
                    .thenReturn(new PageImpl<>(List.of(p)));

            // Search with completely different criteria: score = 0
            List<DuplicateCandidateDTO> result = service.getDuplicateCandidates(
                    "XYZ NOMATCH", null, null, hospitalId);

            assertThat(result).isEmpty();
        }
    }

    // ── addToWaitlist ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addToWaitlist()")
    class AddToWaitlist {

        @Test
        @DisplayName("creates a waitlist entry and returns response")
        void createsEntry() {
            WaitlistEntryRequestDTO req = new WaitlistEntryRequestDTO();
            req.setDepartmentId(departmentId);
            req.setPatientId(patientId);
            req.setPreferredProviderId(null);
            req.setRequestedDateFrom(today);
            req.setRequestedDateTo(today.plusDays(7));
            req.setReason("Follow-up needed");

            when(hospitalRepo.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(departmentRepo.findById(departmentId)).thenReturn(Optional.of(department));
            when(patientRepo.findById(patientId)).thenReturn(Optional.of(patient));

            AppointmentWaitlist saved = mock(AppointmentWaitlist.class);
            when(saved.getId()).thenReturn(UUID.randomUUID());
            when(saved.getHospital()).thenReturn(hospital);
            when(saved.getDepartment()).thenReturn(department);
            when(saved.getPatient()).thenReturn(patient);
            when(saved.getPreferredProvider()).thenReturn(null);
            when(saved.getStatus()).thenReturn("WAITING");
            when(saved.getPriority()).thenReturn("ROUTINE");
            when(saved.getReason()).thenReturn("Follow-up needed");
            when(saved.getRequestedDateFrom()).thenReturn(today);
            when(saved.getRequestedDateTo()).thenReturn(today.plusDays(7));
            patient.setHospitalRegistrations(Collections.emptySet());

            when(waitlistRepo.save(any())).thenReturn(saved);

            WaitlistEntryResponseDTO result = service.addToWaitlist(req, hospitalId, "receptionist1");

            assertThat(result.getStatus()).isEqualTo("WAITING");
            assertThat(result.getPatientName()).isEqualTo("John Doe");
            verify(waitlistRepo).save(any(AppointmentWaitlist.class));
        }

        @Test
        @DisplayName("throws when hospital not found")
        void throwsWhenHospitalNotFound() {
            WaitlistEntryRequestDTO req = new WaitlistEntryRequestDTO();
            req.setDepartmentId(departmentId);
            req.setPatientId(patientId);
            when(hospitalRepo.findById(hospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addToWaitlist(req, hospitalId, "user"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── getWaitlist ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getWaitlist()")
    class GetWaitlist {

        @Test
        @DisplayName("returns filtered waitlist entries")
        void returnsEntries() {
            AppointmentWaitlist entry = mock(AppointmentWaitlist.class);
            when(entry.getId()).thenReturn(UUID.randomUUID());
            when(entry.getHospital()).thenReturn(hospital);
            when(entry.getDepartment()).thenReturn(department);
            when(entry.getPatient()).thenReturn(patient);
            when(entry.getPreferredProvider()).thenReturn(null);
            when(entry.getStatus()).thenReturn("WAITING");
            patient.setHospitalRegistrations(Collections.emptySet());

            when(waitlistRepo.findByHospitalFiltered(hospitalId, null, null))
                    .thenReturn(List.of(entry));

            List<WaitlistEntryResponseDTO> result = service.getWaitlist(hospitalId, null, null);

            assertThat(result).hasSize(1);
        }
    }

    // ── offerWaitlistSlot ────────────────────────────────────────────────────

    @Nested
    @DisplayName("offerWaitlistSlot()")
    class OfferWaitlistSlot {

        @Test
        @DisplayName("changes status to OFFERED")
        void changesStatusToOffered() {
            UUID waitlistId = UUID.randomUUID();
            AppointmentWaitlist entry = mock(AppointmentWaitlist.class);
            when(entry.getId()).thenReturn(waitlistId);
            when(entry.getHospital()).thenReturn(hospital);
            when(entry.getDepartment()).thenReturn(department);
            when(entry.getPatient()).thenReturn(patient);
            when(entry.getPreferredProvider()).thenReturn(null);
            when(entry.getStatus()).thenReturn("OFFERED");
            patient.setHospitalRegistrations(Collections.emptySet());

            when(waitlistRepo.findByIdAndHospital_Id(waitlistId, hospitalId))
                    .thenReturn(Optional.of(entry));
            when(waitlistRepo.save(entry)).thenReturn(entry);

            WaitlistEntryResponseDTO result = service.offerWaitlistSlot(waitlistId, hospitalId);

            assertThat(result.getStatus()).isEqualTo("OFFERED");
        }

        @Test
        @DisplayName("throws when waitlist entry not found")
        void throwsWhenNotFound() {
            UUID waitlistId = UUID.randomUUID();
            when(waitlistRepo.findByIdAndHospital_Id(waitlistId, hospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.offerWaitlistSlot(waitlistId, hospitalId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── closeWaitlistEntry ───────────────────────────────────────────────────

    @Nested
    @DisplayName("closeWaitlistEntry()")
    class CloseWaitlistEntry {

        @Test
        @DisplayName("sets status to CLOSED")
        void closesEntry() {
            UUID waitlistId = UUID.randomUUID();
            AppointmentWaitlist entry = mock(AppointmentWaitlist.class);
            when(waitlistRepo.findByIdAndHospital_Id(waitlistId, hospitalId))
                    .thenReturn(Optional.of(entry));

            service.closeWaitlistEntry(waitlistId, hospitalId);

            verify(entry).setStatus("CLOSED");
            verify(waitlistRepo).save(entry);
        }

        @Test
        @DisplayName("throws when entry not found")
        void throwsWhenNotFound() {
            UUID waitlistId = UUID.randomUUID();
            when(waitlistRepo.findByIdAndHospital_Id(waitlistId, hospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.closeWaitlistEntry(waitlistId, hospitalId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── attestEligibility ────────────────────────────────────────────────────

    @Nested
    @DisplayName("attestEligibility()")
    class AttestEligibility {

        @Test
        @DisplayName("updates insurance verification fields")
        void updatesInsurance() {
            UUID insuranceId = UUID.randomUUID();
            PatientInsurance insurance = mock(PatientInsurance.class);
            when(insuranceRepo.findByIdAndAssignment_Hospital_Id(insuranceId, hospitalId))
                    .thenReturn(Optional.of(insurance));

            EligibilityAttestationRequestDTO req = new EligibilityAttestationRequestDTO();
            req.setEligibilityNotes("Verified via phone");

            service.attestEligibility(insuranceId, hospitalId, "staff1", req);

            verify(insurance).setVerifiedBy("staff1");
            verify(insurance).setEligibilityNotes("Verified via phone");
            verify(insuranceRepo).save(insurance);
        }

        @Test
        @DisplayName("throws when insurance not found")
        void throwsWhenNotFound() {
            UUID insuranceId = UUID.randomUUID();
            when(insuranceRepo.findByIdAndAssignment_Hospital_Id(insuranceId, hospitalId))
                    .thenReturn(Optional.empty());

            EligibilityAttestationRequestDTO req = new EligibilityAttestationRequestDTO();

            assertThatThrownBy(() -> service.attestEligibility(insuranceId, hospitalId, "staff1", req))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── updateEncounterStatus ────────────────────────────────────────────────

    @Nested
    @DisplayName("updateEncounterStatus()")
    class UpdateEncounterStatus {

        @Test
        @DisplayName("updates encounter status")
        void updatesStatus() {
            UUID encounterId = UUID.randomUUID();
            Encounter encounter = mock(Encounter.class);
            when(encounterRepo.findByIdAndHospital_Id(encounterId, hospitalId))
                    .thenReturn(Optional.of(encounter));

            service.updateEncounterStatus(encounterId, EncounterStatus.COMPLETED, hospitalId);

            verify(encounter).setStatus(EncounterStatus.COMPLETED);
            verify(encounterRepo).save(encounter);
        }

        @Test
        @DisplayName("throws when encounter not found")
        void throwsWhenNotFound() {
            UUID encounterId = UUID.randomUUID();
            when(encounterRepo.findByIdAndHospital_Id(encounterId, hospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateEncounterStatus(encounterId, EncounterStatus.COMPLETED, hospitalId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
