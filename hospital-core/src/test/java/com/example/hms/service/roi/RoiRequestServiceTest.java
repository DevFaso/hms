package com.example.hms.service.roi;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.RoiRequestStatus;
import com.example.hms.enums.RoiRequesterType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.RoiRequestMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.RoiRequest;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.roi.RoiDecisionDTO;
import com.example.hms.payload.dto.roi.RoiRequestCreateDTO;
import com.example.hms.payload.dto.roi.RoiSelfRequestCreateDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoiRequestRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The contracts worth defending (Tier 2 item 39b): fulfilment emits BOTH
 * its own trail entry AND the PATIENT_EXPORT disclosure row keyed by
 * patient — the row item 39's report shows as COPY_RELEASED — and is
 * REFUSED when the disclosure row cannot persist; audit descriptions
 * carry no request narrative (event_description is plaintext); denial
 * needs a reason; a third-party intake needs the requester's name; a
 * decided request refuses a second decision; foreign and nonexistent
 * rows collapse to the identical not-found; concurrent decisions get a
 * clean retryable refusal.
 */
@ExtendWith(MockitoExtension.class)
class RoiRequestServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 4, 9, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @Mock private RoiRequestRepository roiRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditService;

    private RoiRequestService service;

    private UUID hospitalId;
    private UUID patientId;
    private Hospital hospital;
    private Patient patient;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        // The real mapper: the entity→DTO contract is part of what these
        // tests defend, and a lenient mock could hide a dropped field.
        service = new RoiRequestService(roiRepository, patientRepository, hospitalRepository,
            staffRepository, roleValidator, auditService, new RoiRequestMapper(), clock);

        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General");

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Awa");
        patient.setLastName("Traore");
        PatientHospitalRegistration reg = new PatientHospitalRegistration();
        reg.setHospital(hospital);
        reg.setActive(true);
        Set<PatientHospitalRegistration> regs = new HashSet<>();
        regs.add(reg);
        patient.setHospitalRegistrations(regs);
    }

    private void asClinicianAtHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    private void auditSinkUp() {
        when(auditService.logEvent(any())).thenReturn(AuditEventLogResponseDTO.builder().build());
    }

    private RoiRequestCreateDTO createDto() {
        return RoiRequestCreateDTO.builder()
            .requesterType(RoiRequesterType.THIRD_PARTY)
            .requesterName("Cabinet Ouédraogo")
            .requesterContact("cabinet@example.bf")
            .purpose("Insurance claim")
            .scopeDescription("Full record")
            .build();
    }

    private RoiSelfRequestCreateDTO selfDto() {
        return RoiSelfRequestCreateDTO.builder()
            .requesterContact("awa@example.bf")
            .purpose("My own copy")
            .scopeDescription("Full record")
            .build();
    }

    private RoiRequest pendingRequest() {
        RoiRequest request = RoiRequest.builder()
            .patientId(patientId)
            .patientName("Awa Traore")
            .hospital(hospital)
            .requesterType(RoiRequesterType.THIRD_PARTY)
            .requesterName("Cabinet Ouédraogo")
            .purpose("Insurance claim")
            .scopeDescription("Full record")
            .status(RoiRequestStatus.PENDING)
            .requestedOn(TODAY.minusDays(2))
            .build();
        request.setId(UUID.randomUUID());
        return request;
    }

    // ── intake ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("create logs a PENDING row dated today, snapshots the patient name, audits ROI_REQUESTED narrative-free")
    void createLogsPendingRow() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(roiRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.create(patientId, createDto());

        assertThat(dto.getStatus()).isEqualTo(RoiRequestStatus.PENDING);
        assertThat(dto.getRequestedOn()).isEqualTo(TODAY);
        assertThat(dto.getRequesterName()).isEqualTo("Cabinet Ouédraogo");
        // The snapshot that keeps the legal record legible after a purge.
        assertThat(dto.getPatientName()).isEqualTo(patient.getFullName());
        ArgumentCaptor<AuditEventRequestDTO> audit =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.ROI_REQUESTED);
        // event_description is a PLAINTEXT column - no encrypted narrative
        // may be copied into it.
        assertThat(audit.getValue().getEventDescription())
            .doesNotContain("Full record")
            .doesNotContain("Cabinet Ouédraogo")
            .doesNotContain("Insurance claim");
    }

    @Test
    @DisplayName("a third-party intake without the requester's name is refused")
    void thirdPartyIntakeNeedsAName() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        RoiRequestCreateDTO dto = createDto();
        dto.setRequesterName("   ");

        assertThatThrownBy(() -> service.create(patientId, dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("requester's name");
        verify(roiRepository, never()).save(any());
    }

    @Test
    @DisplayName("create refuses a future request date")
    void createRefusesFutureDate() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        RoiRequestCreateDTO dto = createDto();
        dto.setRequestedOn(TODAY.plusDays(1));

        assertThatThrownBy(() -> service.create(patientId, dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("future");
        verify(roiRepository, never()).save(any());
    }

    @Test
    @DisplayName("create collapses a patient not registered here to not-found")
    void createRefusesForeignPatient() {
        asClinicianAtHospital();
        patient.setHospitalRegistrations(new HashSet<>());
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        RoiRequestCreateDTO dto = createDto();

        assertThatThrownBy(() -> service.create(patientId, dto))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(roiRepository, never()).save(any());
    }

    @Test
    @DisplayName("createForSelf files as PATIENT with the patient's own name — the self DTO has no identity to spoof")
    void createForSelfForcesPatientIdentity() {
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(roiRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var saved = service.createForSelf(patient, hospitalId, selfDto());

        assertThat(saved.getRequesterType()).isEqualTo(RoiRequesterType.PATIENT);
        assertThat(saved.getRequesterName()).isEqualTo(patient.getFullName());
        assertThat(saved.getRequesterContact()).isEqualTo("awa@example.bf");
    }

    @Test
    @DisplayName("createForSelf refuses a patient with no registered hospital")
    void createForSelfNeedsAHospital() {
        RoiSelfRequestCreateDTO dto = selfDto();
        assertThatThrownBy(() -> service.createForSelf(patient, null, dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("registered hospital");
    }

    // ── decisions ───────────────────────────────────────────────────────

    @Test
    @DisplayName("fulfil emits BOTH its trail entry AND the PATIENT_EXPORT disclosure keyed by patient — narrative-free")
    void fulfilEmitsTheDisclosureRow() {
        asClinicianAtHospital();
        auditSinkUp();
        RoiRequest request = pendingRequest();
        when(roiRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(roiRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.fulfil(request.getId(), RoiDecisionDTO.builder().build());

        assertThat(dto.getStatus()).isEqualTo(RoiRequestStatus.FULFILLED);
        assertThat(dto.getDecidedAt()).isEqualTo(NOW);

        // The whole point of the workflow: the release lands in item 39's
        // accounting. PATIENT_EXPORT + patientId = COPY_RELEASED on the
        // patient's own report.
        ArgumentCaptor<AuditEventRequestDTO> audit =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService, times(2)).logEvent(audit.capture());
        assertThat(audit.getAllValues())
            .extracting(AuditEventRequestDTO::getEventType, AuditEventRequestDTO::getPatientId)
            .containsExactlyInAnyOrder(
                tuple(AuditEventType.ROI_FULFILLED, patientId),
                tuple(AuditEventType.PATIENT_EXPORT, patientId));
        // No request narrative in either plaintext description; resourceId
        // links the rows to the encrypted request.
        assertThat(audit.getAllValues())
            .allSatisfy(a -> assertThat(a.getEventDescription())
                .doesNotContain("Full record")
                .doesNotContain("Cabinet Ouédraogo")
                .doesNotContain("Insurance claim"));
    }

    @Test
    @DisplayName("fulfilment is REFUSED when the disclosure row cannot persist — no FULFILLED without PATIENT_EXPORT")
    void fulfilRefusedWhenDisclosureCannotPersist() {
        asClinicianAtHospital();
        RoiRequest request = pendingRequest();
        when(roiRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(roiRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        // The audit sink's failure mode is a null return, never a throw.
        when(auditService.logEvent(any())).thenReturn(null);
        RoiDecisionDTO decision = RoiDecisionDTO.builder().build();
        UUID requestId = request.getId();

        assertThatThrownBy(() -> service.fulfil(requestId, decision))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("disclosure log");
    }

    @Test
    @DisplayName("deny needs a reason — it is the outcome the requester is told")
    void denyNeedsAReason() {
        asClinicianAtHospital();
        RoiDecisionDTO blank = RoiDecisionDTO.builder().note("  ").build();
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> service.deny(requestId, blank))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason");
        verify(roiRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("deny closes the request with the note and stays OFF the disclosure report")
    void denyClosesWithNote() {
        asClinicianAtHospital();
        RoiRequest request = pendingRequest();
        when(roiRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(roiRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.deny(request.getId(),
            RoiDecisionDTO.builder().note("No valid authorisation presented").build());

        assertThat(dto.getStatus()).isEqualTo(RoiRequestStatus.DENIED);
        assertThat(dto.getDecisionNote()).isEqualTo("No valid authorisation presented");
        ArgumentCaptor<AuditEventRequestDTO> audit =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(audit.capture());
        // Nothing was disclosed — no PATIENT_EXPORT row.
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.ROI_DENIED);
    }

    @Test
    @DisplayName("a decided request refuses a second decision")
    void decidedRequestRefusesASecondDecision() {
        asClinicianAtHospital();
        RoiRequest request = pendingRequest();
        request.setStatus(RoiRequestStatus.FULFILLED);
        when(roiRepository.findById(request.getId())).thenReturn(Optional.of(request));
        RoiDecisionDTO note = RoiDecisionDTO.builder().note("x").build();
        UUID requestId = request.getId();

        assertThatThrownBy(() -> service.deny(requestId, note))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already FULFILLED");
    }

    @Test
    @DisplayName("foreign and NONEXISTENT requests collapse to the identical not-found")
    void foreignAndUnknownCollapseAlike() {
        asClinicianAtHospital();
        RoiRequest foreign = pendingRequest();
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        foreign.setHospital(other);
        when(roiRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        UUID unknownId = UUID.randomUUID();
        when(roiRepository.findById(unknownId)).thenReturn(Optional.empty());
        RoiDecisionDTO decision = RoiDecisionDTO.builder().build();
        UUID foreignId = foreign.getId();

        Throwable foreignT = catchThrowable(() -> service.fulfil(foreignId, decision));
        Throwable unknownT = catchThrowable(() -> service.fulfil(unknownId, decision));

        assertThat(foreignT).isInstanceOf(ResourceNotFoundException.class);
        assertThat(unknownT).isInstanceOf(ResourceNotFoundException.class);
        assertThat(foreignT.getMessage()).isEqualTo(unknownT.getMessage());
    }

    @Test
    @DisplayName("a concurrent decision surfaces as a retryable refusal, not a silent overwrite")
    void concurrentDecisionIsRefused() {
        asClinicianAtHospital();
        RoiRequest request = pendingRequest();
        when(roiRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(roiRepository.saveAndFlush(any())).thenThrow(
            new ObjectOptimisticLockingFailureException(RoiRequest.class, request.getId()));
        RoiDecisionDTO decision = RoiDecisionDTO.builder().build();
        UUID requestId = request.getId();

        assertThatThrownBy(() -> service.fulfil(requestId, decision))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reload and retry");
    }

    @Test
    @DisplayName("cancel closes a pending request; the decider is recorded")
    void cancelClosesPending() {
        asClinicianAtHospital();
        UUID userId = UUID.randomUUID();
        Staff clerk = new Staff();
        clerk.setId(UUID.randomUUID());
        clerk.setName("Records Clerk");
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(staffRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(clerk));
        RoiRequest request = pendingRequest();
        when(roiRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(roiRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.cancel(request.getId(), RoiDecisionDTO.builder().note("Withdrawn").build());

        assertThat(dto.getStatus()).isEqualTo(RoiRequestStatus.CANCELLED);
        assertThat(dto.getDecidedByName()).isEqualTo("Records Clerk");
    }

    // ── reads ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("worklist defaults to PENDING and clamps the page size")
    void worklistDefaultsToPending() {
        asClinicianAtHospital();
        when(roiRepository.findByHospital_IdAndStatusOrderByRequestedOnAscCreatedAtAsc(
                eq(hospitalId), eq(RoiRequestStatus.PENDING), any(PageRequest.class)))
            .thenAnswer(inv -> {
                PageRequest pr = inv.getArgument(2);
                assertThat(pr.getPageSize()).isEqualTo(RoiRequestService.MAX_WORKLIST_PAGE);
                return new PageImpl<>(List.of(pendingRequest()));
            });

        var page = service.worklist(null, 0, 5000);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getPatientName()).isEqualTo("Awa Traore");
    }

    @Test
    @DisplayName("patientRequests gates on the patient's registration at the caller's hospital")
    void patientRequestsGatesOnTenancy() {
        asClinicianAtHospital();
        patient.setHospitalRegistrations(new HashSet<>());
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service.patientRequests(patientId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("requestsForSelf is null-safe and lists across hospitals")
    void requestsForSelfListsOwn() {
        assertThat(service.requestsForSelf(null)).isEmpty();
        when(roiRepository.findByPatientIdOrderByCreatedAtDesc(patientId))
            .thenReturn(List.of(pendingRequest()));

        assertThat(service.requestsForSelf(patient)).hasSize(1);
    }

    @Test
    @DisplayName("no active hospital context refuses the staff entry points")
    void noHospitalContextRefuses() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        RoiRequestCreateDTO dto = createDto();
        assertThatThrownBy(() -> service.create(patientId, dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital context");
    }

    @Test
    @DisplayName("a trail-entry audit failure never undoes a recorded request")
    void auditFailureIsSwallowed() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(roiRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("audit sink down")).when(auditService).logEvent(any());

        assertThatCode(() -> service.create(patientId, createDto()))
            .doesNotThrowAnyException();
    }
}
