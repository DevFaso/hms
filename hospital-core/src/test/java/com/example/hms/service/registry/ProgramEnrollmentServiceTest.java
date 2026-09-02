package com.example.hms.service.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.CareProgram;
import com.example.hms.enums.ProgramEnrollmentStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ProgramEnrollmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.ProgramEnrollment;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.registry.ProgramEnrollmentRequestDTO;
import com.example.hms.payload.dto.registry.ProgramEnrollmentResponseDTO;
import com.example.hms.payload.dto.registry.ProgramStatusUpdateDTO;
import com.example.hms.payload.dto.registry.ProgramVisitDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.ProgramEnrollmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Disease-programme registries (Tier 2 item 35).
 *
 * <p>Fixed clock throughout: overdue-days and default dates are stamped from
 * it, and a test on the wall clock cannot assert what it stamped. The mapper
 * is the real one — it is pure, and mocking it would leave the DTO shape
 * unasserted.
 */
@ExtendWith(MockitoExtension.class)
class ProgramEnrollmentServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 9, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @Mock private ProgramEnrollmentRepository enrollmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditService;

    private ProgramEnrollmentService service;

    private UUID hospitalId;
    private UUID patientId;
    private Hospital hospital;
    private Patient patient;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new ProgramEnrollmentService(enrollmentRepository, patientRepository,
            hospitalRepository, staffRepository, roleValidator,
            new ProgramEnrollmentMapper(), auditService, clock);

        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General");

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Awa");
        patient.setLastName("Traore");
        patient.setPhoneNumberPrimary("+22670000001");
        PatientHospitalRegistration reg = new PatientHospitalRegistration();
        reg.setHospital(hospital);
        reg.setMrn("MRN-001");
        reg.setActive(true);
        Set<PatientHospitalRegistration> regs = new HashSet<>();
        regs.add(reg);
        patient.setHospitalRegistrations(regs);
    }

    private void asClinicianAtHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    private ProgramEnrollment activeEnrollment() {
        ProgramEnrollment e = ProgramEnrollment.builder()
            .patient(patient)
            .hospital(hospital)
            .program(CareProgram.HIV)
            .status(ProgramEnrollmentStatus.ACTIVE)
            .enrolledOn(TODAY.minusDays(60))
            .visitCadenceDays(30)
            .lastVisitOn(TODAY.minusDays(40))
            .nextExpectedVisit(TODAY.minusDays(10))
            .build();
        e.setId(UUID.randomUUID());
        return e;
    }

    private static ProgramEnrollmentRequestDTO enrollRequest(CareProgram program, int cadence) {
        return ProgramEnrollmentRequestDTO.builder()
            .program(program).visitCadenceDays(cadence).build();
    }

    private static ProgramStatusUpdateDTO statusRequest(ProgramEnrollmentStatus status,
                                                        String reason) {
        return ProgramStatusUpdateDTO.builder().status(status).reason(reason).build();
    }

    // ── enroll ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("enrolment defaults the date to today and derives the first expected visit")
    void enrollDefaultsAndDerives() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
            patientId, hospitalId, CareProgram.HIV, ProgramEnrollmentStatus.ACTIVE))
            .thenReturn(Optional.empty());
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(enrollmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProgramEnrollmentResponseDTO dto = service.enroll(patientId,
            enrollRequest(CareProgram.HIV, 30));

        assertThat(dto.getEnrolledOn()).isEqualTo(TODAY);
        assertThat(dto.getNextExpectedVisit()).isEqualTo(TODAY.plusDays(30));
        assertThat(dto.getStatus()).isEqualTo(ProgramEnrollmentStatus.ACTIVE);
        assertThat(dto.getOverdueDays()).isZero();
        assertThat(dto.getMrn()).isEqualTo("MRN-001");
    }

    @Test
    @DisplayName("a second ACTIVE enrolment in the same programme is refused, not merged")
    void enrollRefusesDuplicate() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
            patientId, hospitalId, CareProgram.TB, ProgramEnrollmentStatus.ACTIVE))
            .thenReturn(Optional.of(activeEnrollment()));
        ProgramEnrollmentRequestDTO request = enrollRequest(CareProgram.TB, 30);

        assertThatThrownBy(() -> service.enroll(patientId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already enrolled");
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("another hospital's patient is a 404, not a 403")
    void enrollIsTenantScoped() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        ProgramEnrollmentRequestDTO request = enrollRequest(CareProgram.HIV, 30);

        assertThatThrownBy(() -> service.enroll(patientId, request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a backdated enrolment derives the expected visit from the given date, not today")
    void enrollHonoursBackfilledDate() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
            any(), any(), any(), any())).thenReturn(Optional.empty());
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(enrollmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDate paperDate = TODAY.minusDays(45);
        ProgramEnrollmentResponseDTO dto = service.enroll(patientId,
            ProgramEnrollmentRequestDTO.builder()
                .program(CareProgram.HYPERTENSION)
                .enrolledOn(paperDate)
                .visitCadenceDays(30)
                .build());

        assertThat(dto.getNextExpectedVisit()).isEqualTo(paperDate.plusDays(30));
        // 45 days ago + 30-day cadence = 15 days overdue already, and the
        // registry must say so rather than resetting the clock at data entry.
        assertThat(dto.getOverdueDays()).isEqualTo(15);
    }

    @Test
    @DisplayName("a successful enrolment emits a patient-keyed audit event")
    void enrollEmitsAudit() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
            any(), any(), any(), any())).thenReturn(Optional.empty());
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(enrollmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.enroll(patientId, enrollRequest(CareProgram.HIV, 30));

        ArgumentCaptor<AuditEventRequestDTO> captor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AuditEventType.PROGRAM_ENROLLED);
        assertThat(captor.getValue().getPatientId()).isEqualTo(patientId);
        assertThat(captor.getValue().getEntityType()).isEqualTo("ProgramEnrollment");
    }

    @Test
    @DisplayName("an audit failure never undoes a recorded enrolment")
    void auditFailureDoesNotBreakTheWrite() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
            any(), any(), any(), any())).thenReturn(Optional.empty());
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(enrollmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(auditService.logEvent(any())).thenThrow(new IllegalStateException("audit down"));

        ProgramEnrollmentResponseDTO dto = service.enroll(patientId,
            enrollRequest(CareProgram.HIV, 30));

        assertThat(dto).isNotNull();
        assertThat(dto.getStatus()).isEqualTo(ProgramEnrollmentStatus.ACTIVE);
    }

    // ── status ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("closing an enrolment without a reason is refused")
    void closeNeedsAReason() {
        asClinicianAtHospital();
        UUID enrollmentId = UUID.randomUUID();
        when(enrollmentRepository.findById(enrollmentId))
            .thenReturn(Optional.of(activeEnrollment()));
        ProgramStatusUpdateDTO request =
            statusRequest(ProgramEnrollmentStatus.LOST_TO_FOLLOW_UP, null);

        assertThatThrownBy(() -> service.updateStatus(patientId, enrollmentId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason");
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("closing stamps the date, the status and the reason together")
    void closeStampsOutcome() {
        asClinicianAtHospital();
        UUID enrollmentId = UUID.randomUUID();
        when(enrollmentRepository.findById(enrollmentId))
            .thenReturn(Optional.of(activeEnrollment()));
        when(enrollmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProgramEnrollmentResponseDTO dto = service.updateStatus(patientId, enrollmentId,
            statusRequest(ProgramEnrollmentStatus.LOST_TO_FOLLOW_UP,
                "Traced twice by phone, once by CHW visit; not found."));

        assertThat(dto.getStatus()).isEqualTo(ProgramEnrollmentStatus.LOST_TO_FOLLOW_UP);
        assertThat(dto.getClosedOn()).isEqualTo(TODAY);
        assertThat(dto.getClosureReason()).contains("CHW");
        // A closed enrolment is never overdue - it has left the denominator.
        assertThat(dto.getOverdueDays()).isZero();
        ArgumentCaptor<AuditEventRequestDTO> captor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(captor.capture());
        assertThat(captor.getValue().getEventType())
            .isEqualTo(AuditEventType.PROGRAM_STATUS_CHANGED);
    }

    @Test
    @DisplayName("re-opening refuses a reason and clears the closure fields")
    void reopenClearsClosure() {
        asClinicianAtHospital();
        ProgramEnrollment enrollment = activeEnrollment();
        enrollment.setStatus(ProgramEnrollmentStatus.WITHDRAWN);
        enrollment.setClosedOn(TODAY.minusDays(5));
        enrollment.setClosureReason("Entered in error");
        UUID enrollmentId = UUID.randomUUID();
        when(enrollmentRepository.findById(enrollmentId)).thenReturn(Optional.of(enrollment));
        ProgramStatusUpdateDTO withReason = statusRequest(ProgramEnrollmentStatus.ACTIVE, "oops");

        assertThatThrownBy(() -> service.updateStatus(patientId, enrollmentId, withReason))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no reason");

        when(enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
            patientId, hospitalId, CareProgram.HIV, ProgramEnrollmentStatus.ACTIVE))
            .thenReturn(Optional.empty());
        when(enrollmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProgramEnrollmentResponseDTO dto = service.updateStatus(patientId, enrollmentId,
            statusRequest(ProgramEnrollmentStatus.ACTIVE, null));

        assertThat(dto.getStatus()).isEqualTo(ProgramEnrollmentStatus.ACTIVE);
        assertThat(dto.getClosedOn()).isNull();
        assertThat(dto.getClosureReason()).isNull();
    }

    @Test
    @DisplayName("re-opening cannot create a second ACTIVE enrolment")
    void reopenRefusesSecondActive() {
        asClinicianAtHospital();
        ProgramEnrollment closed = activeEnrollment();
        closed.setStatus(ProgramEnrollmentStatus.COMPLETED);
        UUID enrollmentId = UUID.randomUUID();
        when(enrollmentRepository.findById(enrollmentId)).thenReturn(Optional.of(closed));
        when(enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
            patientId, hospitalId, CareProgram.HIV, ProgramEnrollmentStatus.ACTIVE))
            .thenReturn(Optional.of(activeEnrollment()));
        ProgramStatusUpdateDTO request = statusRequest(ProgramEnrollmentStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.updateStatus(patientId, enrollmentId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already has an active");
    }

    @Test
    @DisplayName("another hospital's enrolment row is a 404, not a 403")
    void statusIsTenantScoped() {
        asClinicianAtHospital();
        ProgramEnrollment foreign = activeEnrollment();
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        foreign.setHospital(other);
        UUID enrollmentId = UUID.randomUUID();
        when(enrollmentRepository.findById(enrollmentId)).thenReturn(Optional.of(foreign));
        ProgramStatusUpdateDTO request = statusRequest(ProgramEnrollmentStatus.COMPLETED, "done");

        assertThatThrownBy(() -> service.updateStatus(patientId, enrollmentId, request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── visits ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a visit advances the next expected visit by the cadence")
    void visitAdvancesCadence() {
        asClinicianAtHospital();
        UUID enrollmentId = UUID.randomUUID();
        when(enrollmentRepository.findById(enrollmentId))
            .thenReturn(Optional.of(activeEnrollment()));
        when(enrollmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProgramEnrollmentResponseDTO dto = service.recordVisit(patientId, enrollmentId, null);

        assertThat(dto.getLastVisitOn()).isEqualTo(TODAY);
        assertThat(dto.getNextExpectedVisit()).isEqualTo(TODAY.plusDays(30));
        assertThat(dto.getOverdueDays()).isZero();
    }

    @Test
    @DisplayName("a visit on a closed enrolment is refused")
    void visitNeedsActive() {
        asClinicianAtHospital();
        ProgramEnrollment enrollment = activeEnrollment();
        enrollment.setStatus(ProgramEnrollmentStatus.LOST_TO_FOLLOW_UP);
        UUID enrollmentId = UUID.randomUUID();
        when(enrollmentRepository.findById(enrollmentId)).thenReturn(Optional.of(enrollment));
        ProgramVisitDTO request = new ProgramVisitDTO();

        assertThatThrownBy(() -> service.recordVisit(patientId, enrollmentId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active");
    }

    @Test
    @DisplayName("a visit before the enrolment date is a typo, refused")
    void visitCannotPredateEnrollment() {
        asClinicianAtHospital();
        ProgramEnrollment enrollment = activeEnrollment();
        UUID enrollmentId = UUID.randomUUID();
        when(enrollmentRepository.findById(enrollmentId)).thenReturn(Optional.of(enrollment));
        ProgramVisitDTO request = ProgramVisitDTO.builder()
            .visitDate(enrollment.getEnrolledOn().minusDays(1)).build();

        assertThatThrownBy(() -> service.recordVisit(patientId, enrollmentId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("predate");
    }

    @Test
    @DisplayName("a visit older than the last recorded one cannot move the schedule backwards")
    void visitCannotBeOutOfOrder() {
        // The summary pair tracks the LATEST visit; letting an older
        // backfill through would move nextExpectedVisit backwards and mark
        // an adherent patient overdue.
        asClinicianAtHospital();
        ProgramEnrollment enrollment = activeEnrollment();
        UUID enrollmentId = UUID.randomUUID();
        when(enrollmentRepository.findById(enrollmentId)).thenReturn(Optional.of(enrollment));
        ProgramVisitDTO request = ProgramVisitDTO.builder()
            .visitDate(enrollment.getLastVisitOn().minusDays(1)).build();

        assertThatThrownBy(() -> service.recordVisit(patientId, enrollmentId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("last recorded");
        verify(enrollmentRepository, never()).save(any());
    }

    // ── registry ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the registry page computes overdue days server-side")
    void registryComputesOverdue() {
        asClinicianAtHospital();
        when(enrollmentRepository.findRegistry(any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(java.util.List.of(activeEnrollment())));

        Page<ProgramEnrollmentResponseDTO> rows = service.registry(CareProgram.HIV, null, 0, 100);

        assertThat(rows.getContent()).hasSize(1);
        assertThat(rows.getContent().get(0).getOverdueDays()).isEqualTo(10);
    }

    @Test
    @DisplayName("the page size is capped server-side - a cohort read is never a dump")
    void registryCapsPageSize() {
        asClinicianAtHospital();
        when(enrollmentRepository.findRegistry(any(), any(), any(), any(Pageable.class)))
            .thenReturn(Page.empty());

        service.registry(CareProgram.HIV, null, 0, 100_000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(enrollmentRepository).findRegistry(any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getPageSize())
            .isEqualTo(ProgramEnrollmentService.MAX_REGISTRY_PAGE);
    }

    @Test
    @DisplayName("no active hospital context refuses rather than listing every hospital")
    void registryNeedsAHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        assertThatThrownBy(() -> service.registry(CareProgram.HIV, null, 0, 100))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("hospital");
    }

    @Test
    @DisplayName("the saved row carries what the clinician typed, nothing invented")
    void savedRowCarriesInput() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
            any(), any(), any(), any())).thenReturn(Optional.empty());
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(enrollmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.enroll(patientId, ProgramEnrollmentRequestDTO.builder()
            .program(CareProgram.ANC)
            .visitCadenceDays(14)
            .notes("  Referred from CSPS  ")
            .build());

        ArgumentCaptor<ProgramEnrollment> captor = ArgumentCaptor.forClass(ProgramEnrollment.class);
        verify(enrollmentRepository).save(captor.capture());
        assertThat(captor.getValue().getVisitCadenceDays()).isEqualTo(14);
        assertThat(captor.getValue().getNotes()).isEqualTo("Referred from CSPS");
    }
}
