package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Role;
import com.example.hms.model.UserRoleHospitalAssignment;
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
import com.example.hms.enums.SlotStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.scheduling.AppointmentSlot;
import com.example.hms.payload.dto.scheduling.AppointmentSlotDTO;
import com.example.hms.repository.scheduling.AppointmentSlotRepository;
import com.example.hms.service.scheduling.PatientOutreachNotifier;
import com.example.hms.service.scheduling.SlotInventoryService;
import com.example.hms.payload.dto.CheckInRequestDTO;
import com.example.hms.payload.dto.CheckInResponseDTO;
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
import org.springframework.security.access.AccessDeniedException;
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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private com.example.hms.repository.UserRepository userRepo;
    @Mock private com.example.hms.service.TreatmentConsentService treatmentConsentService;
    @Mock private AppointmentSlotRepository slotRepo;
    @Mock private SlotInventoryService slotInventoryService;
    @Mock private PatientOutreachNotifier outreachNotifier;
    @Mock private org.springframework.context.MessageSource messageSource;

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

        // @Value field — never injected by Mockito, and a null language tag NPEs.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "outreachLocale", "en");
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
        lenient().when(invoiceRepo.existsOutstandingBalance(any(), any())).thenReturn(false);
    }

    // ── getDashboardSummary ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getDashboardSummary()")
    class GetDashboardSummary {

        @Test
        @DisplayName("waitingCount is arrivedCount minus inProgressCount (never negative)")
        void waitingCountIsArrivedMinusInProgress() {
            Appointment appt1 = makeAppointment(AppointmentStatus.SCHEDULED);
            Appointment appt2 = makeAppointment(AppointmentStatus.SCHEDULED);
            Encounter arrived1 = makeEncounter(EncounterStatus.ARRIVED, appt1);
            Encounter arrived2 = makeEncounter(EncounterStatus.ARRIVED, appt2);
            Encounter inProgress = makeEncounter(EncounterStatus.IN_PROGRESS, appt1);

            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(appt1, appt2));
            when(encounterRepo.findByAppointmentIdIn(any()))
                    .thenReturn(List.of(arrived1, arrived2, inProgress));
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(Collections.emptyList());

            ReceptionDashboardSummaryDTO result = service.getDashboardSummary(today, hospitalId);

            // 2 arrived, 1 in-progress → waitingCount = max(0, 2-1) = 1
            assertThat(result.getWaitingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("waitingCount is 0 when inProgressCount exceeds arrivedCount")
        void waitingCountIsNonNegative() {
            Appointment appt = makeAppointment(AppointmentStatus.SCHEDULED);
            Encounter inProgress = makeEncounter(EncounterStatus.IN_PROGRESS, appt);

            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(appt));
            when(encounterRepo.findByAppointmentIdIn(any())).thenReturn(List.of(inProgress));
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(Collections.emptyList());

            ReceptionDashboardSummaryDTO result = service.getDashboardSummary(today, hospitalId);

            assertThat(result.getWaitingCount()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("completedCount counts linked encounters and walk-ins but not appointments")
        void completedCountExcludesRawAppointments() {
            Appointment completed = makeAppointment(AppointmentStatus.COMPLETED);
            Encounter linkedCompleted = makeEncounter(EncounterStatus.COMPLETED, completed);
            Encounter walkInCompleted = makeWalkInEncounter(EncounterStatus.COMPLETED);

            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(List.of(completed));
            when(encounterRepo.findByAppointmentIdIn(any())).thenReturn(List.of(linkedCompleted));
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(List.of(walkInCompleted));

            ReceptionDashboardSummaryDTO result = service.getDashboardSummary(today, hospitalId);

            // 1 linked encounter + 1 walk-in = 2 (appointment status COMPLETED not counted separately)
            assertThat(result.getCompletedCount()).isEqualTo(2);
        }

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
            assertThat(result.getCompletedCount()).isZero(); // no linked encounter or walk-in with COMPLETED
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
            lenient().when(walkIn.getHospital()).thenReturn(hospital);
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(List.of(walkIn));
            stubEmptyInsuranceAndInvoices();

            List<ReceptionQueueItemDTO> result = service.getQueue(today, hospitalId, null, null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getAppointmentReason()).isEqualTo("Walk-in");
        }

        @Test
        @DisplayName("walk-in hasInsuranceIssue is true when patient has no insurance")
        void walkInInsuranceFlagSet() {
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(Collections.emptyList());
            Encounter walkIn = makeWalkInEncounter(EncounterStatus.ARRIVED);
            lenient().when(walkIn.getHospital()).thenReturn(hospital);
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(List.of(walkIn));
            // No insurance → detectInsuranceIssue returns true
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
                    .thenReturn(Collections.emptyList());
            when(invoiceRepo.existsOutstandingBalance(patientId, hospitalId)).thenReturn(false);

            List<ReceptionQueueItemDTO> result = service.getQueue(today, hospitalId, null, null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isHasInsuranceIssue()).isTrue();
            assertThat(result.get(0).isHasOutstandingBalance()).isFalse();
        }

        @Test
        @DisplayName("walk-in hasOutstandingBalance is true when existsOutstandingBalance returns true")
        void walkInOutstandingBalanceFlagSet() {
            when(appointmentRepo.findByHospital_IdAndAppointmentDate(hospitalId, today))
                    .thenReturn(Collections.emptyList());
            Encounter walkIn = makeWalkInEncounter(EncounterStatus.ARRIVED);
            lenient().when(walkIn.getHospital()).thenReturn(hospital);
            when(encounterRepo.findWalkInsForHospitalAndPeriod(eq(hospitalId), any(), any()))
                    .thenReturn(List.of(walkIn));
            PatientInsurance activeIns = mock(PatientInsurance.class);
            lenient().when(activeIns.isPrimary()).thenReturn(true);
            lenient().when(activeIns.getExpirationDate()).thenReturn(null);
            when(insuranceRepo.findByPatient_IdAndAssignment_Hospital_Id(patientId, hospitalId))
                    .thenReturn(List.of(activeIns));
            when(invoiceRepo.existsOutstandingBalance(patientId, hospitalId)).thenReturn(true);

            List<ReceptionQueueItemDTO> result = service.getQueue(today, hospitalId, null, null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isHasInsuranceIssue()).isFalse();
            assertThat(result.get(0).isHasOutstandingBalance()).isTrue();
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

            when(invoiceRepo.existsOutstandingBalance(any(), eq(hospitalId))).thenReturn(true);

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

    // ── waitlist offer lifecycle (P3 #22) ────────────────────────────────────

    private AppointmentSlot offerSlot(LocalDateTime startAt) {
        AppointmentSlot slot = AppointmentSlot.builder()
                .hospital(hospital)
                .slotDate(startAt.toLocalDate())
                .startAt(startAt)
                .endAt(startAt.plusMinutes(30))
                .status(SlotStatus.OPEN)
                .build();
        slot.setId(UUID.randomUUID());
        lenient().when(slotRepo.findById(slot.getId())).thenReturn(Optional.of(slot));
        return slot;
    }

    private AppointmentWaitlist waitingEntry(UUID waitlistId) {
        AppointmentWaitlist entry = mock(AppointmentWaitlist.class);
        lenient().when(entry.getId()).thenReturn(waitlistId);
        lenient().when(entry.getHospital()).thenReturn(hospital);
        lenient().when(entry.getDepartment()).thenReturn(department);
        lenient().when(entry.getPatient()).thenReturn(patient);
        lenient().when(entry.getStatus()).thenReturn("WAITING");
        patient.setHospitalRegistrations(Collections.emptySet());
        lenient().when(waitlistRepo.findByIdAndHospital_Id(waitlistId, hospitalId))
                .thenReturn(Optional.of(entry));
        lenient().when(waitlistRepo.save(entry)).thenReturn(entry);
        return entry;
    }

    @Nested
    @DisplayName("offerWaitlistSlot()")
    class OfferWaitlistSlot {

        @Test
        @DisplayName("holds the slot, stamps the entry OFFERED and notifies the patient")
        void offersASlot() {
            UUID waitlistId = UUID.randomUUID();
            AppointmentWaitlist entry = waitingEntry(waitlistId);
            AppointmentSlot slot = offerSlot(LocalDateTime.now().plusDays(7));
            when(messageSource.getMessage(eq("sms.waitlist.offer"), any(), any(Locale.class)))
                    .thenReturn("offer message");

            WaitlistEntryResponseDTO result =
                    service.offerWaitlistSlot(waitlistId, hospitalId, slot.getId(), 48);

            verify(slotInventoryService).hold(eq(slot.getId()), anyInt());
            verify(entry).setOfferedSlot(slot);
            verify(entry).setStatus("OFFERED");
            verify(entry).setOfferExpiresAt(any(LocalDateTime.class));
            verify(outreachNotifier).notifyPatient(patient, "offer message");
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("caps the offer expiry at the slot's start time")
        void capsExpiryAtSlotStart() {
            // A 48-hour offer on a slot six hours away must lapse when the slot
            // starts, or accepting a stale offer could book the past.
            UUID waitlistId = UUID.randomUUID();
            AppointmentWaitlist entry = waitingEntry(waitlistId);
            AppointmentSlot slot = offerSlot(LocalDateTime.now().plusHours(6));

            service.offerWaitlistSlot(waitlistId, hospitalId, slot.getId(), 48);

            verify(entry).setOfferExpiresAt(slot.getStartAt());
        }

        @Test
        @DisplayName("refuses an entry that is not waiting")
        void refusesNonWaitingEntry() {
            UUID waitlistId = UUID.randomUUID();
            AppointmentWaitlist entry = waitingEntry(waitlistId);
            when(entry.getStatus()).thenReturn("CLOSED");
            UUID slotId = UUID.randomUUID();

            assertThatThrownBy(() -> service.offerWaitlistSlot(waitlistId, hospitalId, slotId, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("waiting entry");
            verify(slotInventoryService, never()).hold(any(), anyInt());
        }

        @Test
        @DisplayName("a slot from another hospital reads as not found")
        void otherHospitalSlotIs404() {
            UUID waitlistId = UUID.randomUUID();
            waitingEntry(waitlistId);
            Hospital other = mock(Hospital.class);
            lenient().when(other.getId()).thenReturn(UUID.randomUUID());
            AppointmentSlot slot = offerSlot(LocalDateTime.now().plusDays(7));
            slot.setHospital(other);

            UUID slotId = slot.getId();
            assertThatThrownBy(() -> service.offerWaitlistSlot(waitlistId, hospitalId, slotId, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("throws when waitlist entry not found")
        void throwsWhenNotFound() {
            UUID waitlistId = UUID.randomUUID();
            when(waitlistRepo.findByIdAndHospital_Id(waitlistId, hospitalId)).thenReturn(Optional.empty());
            UUID slotId = UUID.randomUUID();

            assertThatThrownBy(() -> service.offerWaitlistSlot(waitlistId, hospitalId, slotId, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("acceptWaitlistOffer()")
    class AcceptWaitlistOffer {

        @Test
        @DisplayName("books the offered slot and closes the entry")
        void booksAndCloses() {
            UUID waitlistId = UUID.randomUUID();
            AppointmentWaitlist entry = waitingEntry(waitlistId);
            AppointmentSlot slot = offerSlot(LocalDateTime.now().plusDays(3));
            slot.setStatus(SlotStatus.HELD);
            when(entry.getStatus()).thenReturn("OFFERED");
            when(entry.getOfferedSlot()).thenReturn(slot);
            when(entry.getOfferExpiresAt()).thenReturn(LocalDateTime.now().plusHours(12));
            when(entry.getReason()).thenReturn("Back pain");

            UUID appointmentId = UUID.randomUUID();
            when(slotInventoryService.book(slot.getId(), patientId, "Back pain"))
                    .thenReturn(AppointmentSlotDTO.builder().appointmentId(appointmentId).build());
            Appointment appointment = mock(Appointment.class);
            when(appointmentRepo.findById(appointmentId)).thenReturn(Optional.of(appointment));

            service.acceptWaitlistOffer(waitlistId, hospitalId);

            // The offer-hold is freed first so booking can claim the slot.
            verify(slotInventoryService).release(slot.getId());
            verify(slotInventoryService).book(slot.getId(), patientId, "Back pain");
            verify(entry).setOfferedAppointment(appointment);
            verify(entry).setStatus("CLOSED");
        }

        @Test
        @DisplayName("refuses an expired offer")
        void refusesExpiredOffer() {
            UUID waitlistId = UUID.randomUUID();
            AppointmentWaitlist entry = waitingEntry(waitlistId);
            AppointmentSlot slot = offerSlot(LocalDateTime.now().plusDays(3));
            when(entry.getStatus()).thenReturn("OFFERED");
            when(entry.getOfferedSlot()).thenReturn(slot);
            when(entry.getOfferExpiresAt()).thenReturn(LocalDateTime.now().minusMinutes(5));

            assertThatThrownBy(() -> service.acceptWaitlistOffer(waitlistId, hospitalId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("expired");
            verify(slotInventoryService, never()).book(any(), any(), any());
        }

        @Test
        @DisplayName("refuses an entry with no open offer")
        void refusesWithoutOffer() {
            UUID waitlistId = UUID.randomUUID();
            waitingEntry(waitlistId);

            assertThatThrownBy(() -> service.acceptWaitlistOffer(waitlistId, hospitalId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("no open offer");
        }
    }

    @Nested
    @DisplayName("declineWaitlistOffer()")
    class DeclineWaitlistOffer {

        @Test
        @DisplayName("frees the held slot and returns the entry to WAITING")
        void freesSlotAndRewaits() {
            UUID waitlistId = UUID.randomUUID();
            AppointmentWaitlist entry = waitingEntry(waitlistId);
            AppointmentSlot slot = offerSlot(LocalDateTime.now().plusDays(3));
            slot.setStatus(SlotStatus.HELD);
            slot.setHeldUntil(LocalDateTime.now().plusHours(10));
            when(entry.getStatus()).thenReturn("OFFERED");
            when(entry.getOfferedSlot()).thenReturn(slot);

            service.declineWaitlistOffer(waitlistId, hospitalId);

            assertThat(slot.getStatus()).isEqualTo(SlotStatus.OPEN);
            assertThat(slot.getHeldUntil()).isNull();
            verify(entry).setStatus("WAITING");
            verify(entry).setOfferedSlot(null);
            verify(entry).setOfferExpiresAt(null);
        }
    }

    @Nested
    @DisplayName("reconcileExpiredWaitlistOffers()")
    class ReconcileExpiredWaitlistOffers {

        @Test
        @DisplayName("returns lapsed offers to WAITING and frees their slots")
        void reconcilesLapsedOffers() {
            AppointmentWaitlist entry = waitingEntry(UUID.randomUUID());
            AppointmentSlot slot = offerSlot(LocalDateTime.now().plusDays(1));
            slot.setStatus(SlotStatus.HELD);
            when(entry.getOfferedSlot()).thenReturn(slot);
            when(waitlistRepo.findByStatusAndOfferExpiresAtBefore(eq("OFFERED"), any(LocalDateTime.class)))
                    .thenReturn(List.of(entry));

            assertThat(service.reconcileExpiredWaitlistOffers()).isEqualTo(1);

            assertThat(slot.getStatus()).isEqualTo(SlotStatus.OPEN);
            verify(entry).setStatus("WAITING");
            verify(entry).setOfferedSlot(null);
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
        @DisplayName("admin can update any encounter status")
        void adminUpdatesStatus() {
            UUID encounterId = UUID.randomUUID();
            Encounter encounter = mock(Encounter.class);
            lenient().when(encounter.getStatus()).thenReturn(EncounterStatus.ARRIVED);
            when(encounterRepo.findByIdAndHospital_Id(encounterId, hospitalId))
                    .thenReturn(Optional.of(encounter));

            Role receptionistRole = mock(Role.class);
            when(receptionistRole.getCode()).thenReturn("ROLE_RECEPTIONIST");
            UserRoleHospitalAssignment assignment = mock(UserRoleHospitalAssignment.class);
            when(assignment.getRole()).thenReturn(receptionistRole);
            Staff adminStaff = mock(Staff.class);
            when(adminStaff.getAssignment()).thenReturn(assignment);
            when(staffRepo.findByUsernameOrLicenseOrRoleCode("receptionist1"))
                    .thenReturn(Optional.of(adminStaff));

            service.updateEncounterStatus(encounterId, EncounterStatus.COMPLETED, hospitalId, "receptionist1");

            verify(encounter).setStatus(EncounterStatus.COMPLETED);
            verify(encounterRepo).save(encounter);
        }

        @Test
        @DisplayName("throws when encounter not found")
        void throwsWhenNotFound() {
            UUID encounterId = UUID.randomUUID();
            when(encounterRepo.findByIdAndHospital_Id(encounterId, hospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateEncounterStatus(encounterId, EncounterStatus.COMPLETED, hospitalId, "any"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("throws AccessDeniedException when caller is not assigned to encounter")
        void throwsWhenCallerNotOwner() {
            UUID encounterId = UUID.randomUUID();
            UUID ownerStaffId = UUID.randomUUID();
            UUID callerStaffId = UUID.randomUUID();

            Staff ownerStaff = mock(Staff.class);
            when(ownerStaff.getId()).thenReturn(ownerStaffId);

            Encounter encounter = mock(Encounter.class);
            lenient().when(encounter.getStatus()).thenReturn(EncounterStatus.ARRIVED);
            when(encounter.getStaff()).thenReturn(ownerStaff);
            when(encounterRepo.findByIdAndHospital_Id(encounterId, hospitalId))
                    .thenReturn(Optional.of(encounter));

            // caller is a doctor (not admin/receptionist) — role not privileged
            Role doctorRole = mock(Role.class);
            when(doctorRole.getCode()).thenReturn("ROLE_DOCTOR");
            UserRoleHospitalAssignment callerAssignment = mock(UserRoleHospitalAssignment.class);
            when(callerAssignment.getRole()).thenReturn(doctorRole);
            Staff callerStaff = mock(Staff.class);
            when(callerStaff.getId()).thenReturn(callerStaffId);
            when(callerStaff.getAssignment()).thenReturn(callerAssignment);
            when(staffRepo.findByUsernameOrLicenseOrRoleCode("doctor1"))
                    .thenReturn(Optional.of(callerStaff));

            assertThatThrownBy(() ->
                    service.updateEncounterStatus(encounterId, EncounterStatus.COMPLETED, hospitalId, "doctor1")
            ).isInstanceOf(AccessDeniedException.class);
        }
    }

    // ── MVP 1: Patient Check-In Tests ─────────────────────────────────────────

    @Nested
    @DisplayName("MVP 1 — checkInPatient")
    class CheckInPatientTests {

        @Test
        @DisplayName("successfully checks in a SCHEDULED appointment → creates ARRIVED encounter")
        void checkInScheduledAppointment() {
            UUID appointmentId = UUID.randomUUID();
            UUID staffId = UUID.randomUUID();

            Staff staff = mock(Staff.class);
            lenient().when(staff.getId()).thenReturn(staffId);
            lenient().when(staff.getHospital()).thenReturn(hospital);

            UserRoleHospitalAssignment assignment = mock(UserRoleHospitalAssignment.class);
            lenient().when(assignment.getHospital()).thenReturn(hospital);

            Appointment appointment = new Appointment();
            appointment.setId(appointmentId);
            appointment.setStatus(AppointmentStatus.SCHEDULED);
            appointment.setPatient(patient);
            appointment.setStaff(staff);
            appointment.setHospital(hospital);
            appointment.setDepartment(department);
            appointment.setAssignment(assignment);
            appointment.setAppointmentDate(today);
            appointment.setStartTime(LocalTime.of(9, 0));
            appointment.setEndTime(LocalTime.of(9, 30));

            when(appointmentRepo.findById(appointmentId)).thenReturn(Optional.of(appointment));
            when(encounterRepo.save(any(Encounter.class))).thenAnswer(inv -> {
                Encounter e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });
            when(appointmentRepo.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));

            CheckInRequestDTO request = CheckInRequestDTO.builder()
                    .appointmentId(appointmentId)
                    .chiefComplaint("Headache for 3 days")
                    .identityConfirmed(true)
                    .insuranceVerified(true)
                    .build();

            CheckInResponseDTO response = service.checkInPatient(request, hospitalId, "receptionist1");

            assertThat(response).isNotNull();
            assertThat(response.getAppointmentId()).isEqualTo(appointmentId);
            assertThat(response.getAppointmentStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
            assertThat(response.getEncounterStatus()).isEqualTo(EncounterStatus.ARRIVED);
            assertThat(response.getPatientName()).isEqualTo("John Doe");
            assertThat(response.getArrivalTimestamp()).isNotNull();
            assertThat(response.getChiefComplaint()).isEqualTo("Headache for 3 days");

            verify(appointmentRepo).save(any(Appointment.class));
            verify(encounterRepo).save(any(Encounter.class));
            verify(auditEventLogService).logEvent(any());
            // No consent fields on the request — nothing recorded (P3 #21).
            verify(treatmentConsentService, org.mockito.Mockito.never())
                .record(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("consentObtained=true records a consent-to-treat row (P3 #21)")
        void checkInRecordsConsentWhenObtained() {
            UUID appointmentId = UUID.randomUUID();
            Staff staff = mock(Staff.class);
            lenient().when(staff.getHospital()).thenReturn(hospital);
            UserRoleHospitalAssignment assignment = mock(UserRoleHospitalAssignment.class);
            lenient().when(assignment.getHospital()).thenReturn(hospital);

            Appointment appointment = new Appointment();
            appointment.setId(appointmentId);
            appointment.setStatus(AppointmentStatus.SCHEDULED);
            appointment.setPatient(patient);
            appointment.setStaff(staff);
            appointment.setHospital(hospital);
            appointment.setDepartment(department);
            appointment.setAssignment(assignment);
            appointment.setAppointmentDate(today);
            appointment.setStartTime(LocalTime.of(9, 0));
            appointment.setEndTime(LocalTime.of(9, 30));

            when(appointmentRepo.findById(appointmentId)).thenReturn(Optional.of(appointment));
            when(encounterRepo.save(any(Encounter.class))).thenAnswer(inv -> {
                Encounter e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });
            when(appointmentRepo.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepo.findByUsername("receptionist1")).thenReturn(Optional.empty());

            CheckInRequestDTO request = CheckInRequestDTO.builder()
                    .appointmentId(appointmentId)
                    .identityConfirmed(true)
                    .consentObtained(true)
                    .consentSignedName("John Doe")
                    .build();

            CheckInResponseDTO response = service.checkInPatient(request, hospitalId, "receptionist1");

            assertThat(response.getAppointmentStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
            // Hoisted: patient/hospital are mocks, and calling their getters
            // between eq() registrations corrupts the matcher stack.
            UUID expectedPatientId = patient.getId();
            UUID expectedHospitalId = hospital.getId();
            verify(treatmentConsentService).record(
                org.mockito.ArgumentMatchers.eq(expectedPatientId),
                org.mockito.ArgumentMatchers.eq(expectedHospitalId),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(com.example.hms.enums.TreatmentConsentSource.CHECK_IN),
                org.mockito.ArgumentMatchers.argThat(req ->
                    "John Doe".equals(req.getSignedName())
                        && req.getAppointmentId().equals(appointmentId)));
        }

        @Test
        @DisplayName("a consent-recording failure never rolls back the check-in (P3 #21)")
        void consentFailureDoesNotBlockCheckIn() {
            UUID appointmentId = UUID.randomUUID();
            Staff staff = mock(Staff.class);
            lenient().when(staff.getHospital()).thenReturn(hospital);
            UserRoleHospitalAssignment assignment = mock(UserRoleHospitalAssignment.class);
            lenient().when(assignment.getHospital()).thenReturn(hospital);

            Appointment appointment = new Appointment();
            appointment.setId(appointmentId);
            appointment.setStatus(AppointmentStatus.SCHEDULED);
            appointment.setPatient(patient);
            appointment.setStaff(staff);
            appointment.setHospital(hospital);
            appointment.setDepartment(department);
            appointment.setAssignment(assignment);
            appointment.setAppointmentDate(today);
            appointment.setStartTime(LocalTime.of(9, 0));
            appointment.setEndTime(LocalTime.of(9, 30));

            when(appointmentRepo.findById(appointmentId)).thenReturn(Optional.of(appointment));
            when(encounterRepo.save(any(Encounter.class))).thenAnswer(inv -> {
                Encounter e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });
            when(appointmentRepo.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepo.findByUsername(any())).thenReturn(Optional.empty());
            when(treatmentConsentService.record(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("consent table unavailable"));

            CheckInRequestDTO request = CheckInRequestDTO.builder()
                    .appointmentId(appointmentId)
                    .consentObtained(true)
                    .build();

            CheckInResponseDTO response = service.checkInPatient(request, hospitalId, "receptionist1");

            assertThat(response.getAppointmentStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
        }

        @Test
        @DisplayName("successfully checks in a CONFIRMED appointment")
        void checkInConfirmedAppointment() {
            UUID appointmentId = UUID.randomUUID();
            UUID staffId = UUID.randomUUID();

            Staff staff = mock(Staff.class);
            lenient().when(staff.getId()).thenReturn(staffId);
            lenient().when(staff.getHospital()).thenReturn(hospital);

            UserRoleHospitalAssignment assignment = mock(UserRoleHospitalAssignment.class);
            lenient().when(assignment.getHospital()).thenReturn(hospital);

            Appointment appointment = new Appointment();
            appointment.setId(appointmentId);
            appointment.setStatus(AppointmentStatus.CONFIRMED);
            appointment.setPatient(patient);
            appointment.setStaff(staff);
            appointment.setHospital(hospital);
            appointment.setDepartment(department);
            appointment.setAssignment(assignment);
            appointment.setAppointmentDate(today);
            appointment.setStartTime(LocalTime.of(10, 0));
            appointment.setEndTime(LocalTime.of(10, 30));

            when(appointmentRepo.findById(appointmentId)).thenReturn(Optional.of(appointment));
            when(encounterRepo.save(any(Encounter.class))).thenAnswer(inv -> {
                Encounter e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });
            when(appointmentRepo.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));

            CheckInRequestDTO request = CheckInRequestDTO.builder()
                    .appointmentId(appointmentId)
                    .identityConfirmed(true)
                    .build();

            CheckInResponseDTO response = service.checkInPatient(request, hospitalId, "receptionist1");

            assertThat(response.getAppointmentStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
            assertThat(response.getEncounterStatus()).isEqualTo(EncounterStatus.ARRIVED);
        }

        @Test
        @DisplayName("throws when appointment not found")
        void throwsWhenAppointmentNotFound() {
            UUID appointmentId = UUID.randomUUID();
            when(appointmentRepo.findById(appointmentId)).thenReturn(Optional.empty());

            CheckInRequestDTO request = CheckInRequestDTO.builder()
                    .appointmentId(appointmentId)
                    .identityConfirmed(true)
                    .build();

            assertThatThrownBy(() -> service.checkInPatient(request, hospitalId, "receptionist1"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("throws when appointment is already COMPLETED")
        void throwsWhenAppointmentAlreadyCompleted() {
            UUID appointmentId = UUID.randomUUID();

            Appointment appointment = mock(Appointment.class);
            when(appointment.getId()).thenReturn(appointmentId);
            when(appointment.getStatus()).thenReturn(AppointmentStatus.COMPLETED);
            when(appointment.getHospital()).thenReturn(hospital);
            when(appointmentRepo.findById(appointmentId)).thenReturn(Optional.of(appointment));

            CheckInRequestDTO request = CheckInRequestDTO.builder()
                    .appointmentId(appointmentId)
                    .identityConfirmed(true)
                    .build();

            assertThatThrownBy(() -> service.checkInPatient(request, hospitalId, "receptionist1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("COMPLETED");
        }

        @Test
        @DisplayName("throws when appointment is already CHECKED_IN")
        void throwsWhenAppointmentAlreadyCheckedIn() {
            UUID appointmentId = UUID.randomUUID();

            Appointment appointment = mock(Appointment.class);
            when(appointment.getId()).thenReturn(appointmentId);
            when(appointment.getStatus()).thenReturn(AppointmentStatus.CHECKED_IN);
            when(appointment.getHospital()).thenReturn(hospital);
            when(appointmentRepo.findById(appointmentId)).thenReturn(Optional.of(appointment));

            CheckInRequestDTO request = CheckInRequestDTO.builder()
                    .appointmentId(appointmentId)
                    .identityConfirmed(true)
                    .build();

            assertThatThrownBy(() -> service.checkInPatient(request, hospitalId, "receptionist1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CHECKED_IN");
        }

        @Test
        @DisplayName("throws when appointmentId is null")
        void throwsWhenAppointmentIdNull() {
            CheckInRequestDTO request = CheckInRequestDTO.builder()
                    .identityConfirmed(true)
                    .build();

            assertThatThrownBy(() -> service.checkInPatient(request, hospitalId, "receptionist1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("appointmentId");
        }

        @Test
        @DisplayName("throws AccessDeniedException when appointment belongs to different hospital")
        void throwsWhenAppointmentBelongsToDifferentHospital() {
            UUID appointmentId = UUID.randomUUID();
            UUID otherHospitalId = UUID.randomUUID();
            Hospital otherHospital = mock(Hospital.class);
            when(otherHospital.getId()).thenReturn(otherHospitalId);

            Appointment appointment = mock(Appointment.class);
            when(appointment.getId()).thenReturn(appointmentId);
            when(appointment.getStatus()).thenReturn(AppointmentStatus.SCHEDULED);
            when(appointment.getHospital()).thenReturn(otherHospital);
            when(appointmentRepo.findById(appointmentId)).thenReturn(Optional.of(appointment));

            CheckInRequestDTO request = CheckInRequestDTO.builder()
                    .appointmentId(appointmentId)
                    .identityConfirmed(true)
                    .build();

            assertThatThrownBy(() -> service.checkInPatient(request, hospitalId, "receptionist1"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }
}
