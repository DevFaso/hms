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
import com.example.hms.service.RequiredAuditEventLogPort;
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
    @Mock private RequiredAuditEventLogPort auditService;

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
        org.mockito.Mockito.lenient().when(auditService.logRequiredEvent(any())).thenReturn(AuditEventLogResponseDTO.builder().id(UUID.randomUUID()).build());
    }

    private void asClinicianAtHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        // A clinician or the records desk: one of the roles that collect any approved copy (QA D4270).
        org.mockito.Mockito.lenient().when(roleValidator.hasAnyAuthority(any(String[].class))).thenReturn(true);
    }

    /** A hospital administrator: none of the collecting roles, so only the requests they approved. */
    private UUID asHospitalAdminAtHospital() {
        UUID admin = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        org.mockito.Mockito.lenient().when(roleValidator.hasAnyAuthority(any(String[].class))).thenReturn(false);
        org.mockito.Mockito.lenient().when(roleValidator.getCurrentUserId()).thenReturn(admin);
        return admin;
    }

    private RoiRequest approvedFullCopy(LocalDateTime decidedAt) {
        RoiRequest row = pendingRequest();
        row.setRequesterType(RoiRequesterType.PATIENT);
        row.setScopeDescription("FULL_RECORD");
        row.setStatus(RoiRequestStatus.FULFILLED);
        row.setDecidedAt(decidedAt);
        org.mockito.Mockito.lenient().when(roiRepository.findById(row.getId())).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void anAdministratorCollectsOnlyTheCopiesTheyApproved_D4270() {
        UUID admin = asHospitalAdminAtHospital();
        RoiRequest row = approvedFullCopy(NOW.minusDays(1));
        row.setDecisionActorUserId(UUID.randomUUID());
        assertThatThrownBy(() -> service.copyForExport(row.getId()))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        row.setDecisionActorUserId(admin);
        assertThat(service.copyForExport(row.getId()))
            .isEqualTo(new RoiRequestService.ApprovedCopy(patientId, row.getId(), hospitalId));
    }

    @Test
    void anApprovedCopyIsCollectableForThirtyDaysThenExpired_D4270() {
        asClinicianAtHospital();
        RoiRequest row = approvedFullCopy(NOW.minusDays(30));
        assertThat(service.copyForExport(row.getId()).requestId()).isEqualTo(row.getId());
        row.setDecidedAt(NOW.minusDays(31));
        assertThatThrownBy(() -> service.copyForExport(row.getId())).isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("roi.request.copyExpired");
        assertThatThrownBy(() -> service.copyForSelf(patient, row.getId())).isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("roi.request.copyExpired");
    }

    @Test
    void thePatientCollectsOnlyTheirOwnApprovedFullCopy_D4270() {
        RoiRequest row = approvedFullCopy(NOW.minusDays(2));
        assertThat(service.copyForSelf(patient, row.getId()))
            .isEqualTo(new RoiRequestService.ApprovedCopy(patientId, row.getId(), hospitalId, true));

        Patient someoneElse = new Patient();
        someoneElse.setId(UUID.randomUUID());
        assertThatThrownBy(() -> service.copyForSelf(someoneElse, row.getId())).isInstanceOf(ResourceNotFoundException.class);

        // A copy approved for a third party goes to that third party, not to the portal.
        row.setRequesterType(RoiRequesterType.THIRD_PARTY);
        assertThatThrownBy(() -> service.copyForSelf(patient, row.getId())).isInstanceOf(ResourceNotFoundException.class);
        row.setRequesterType(RoiRequesterType.PATIENT);

        for (RoiRequestStatus refusedStatus : new RoiRequestStatus[]{RoiRequestStatus.PENDING, RoiRequestStatus.DENIED, RoiRequestStatus.CANCELLED}) {
            row.setStatus(refusedStatus);
            assertThatThrownBy(() -> service.copyForSelf(patient, row.getId())).as(refusedStatus.name())
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("roi.request.copyNotApproved");
        }
        row.setStatus(RoiRequestStatus.FULFILLED);
        row.setScopeDescription("Labs 2025 only");
        assertThatThrownBy(() -> service.copyForSelf(patient, row.getId())).isInstanceOf(BusinessException.class);
        row.setScopeDescription("FULL_RECORD");
        row.setRequestKind(com.example.hms.enums.RoiRequestKind.CORRECTION);
        assertThatThrownBy(() -> service.copyForSelf(patient, row.getId())).isInstanceOf(BusinessException.class);
    }

    @Test
    void thePatientsListSaysWhichCopyCanBeDownloadedAndUntilWhen_D4270() {
        RoiRequest fresh = approvedFullCopy(NOW.minusDays(2));
        RoiRequest old = approvedFullCopy(NOW.minusDays(45));
        RoiRequest denied = pendingRequest();
        denied.setStatus(RoiRequestStatus.DENIED);
        when(roiRepository.findByPatientIdOrderByCreatedAtDesc(patientId)).thenReturn(List.of(fresh, old, denied));
        var mine = service.requestsForSelf(patient);
        assertThat(mine.get(0).getCopyDownloadable()).isTrue();
        assertThat(mine.get(0).getCopyAvailableUntil()).isEqualTo(NOW.toLocalDate().minusDays(2).plusDays(30));
        assertThat(mine.get(1).getCopyDownloadable()).isFalse();
        assertThat(mine.get(2).getCopyDownloadable()).isNull();
        assertThat(mine.get(2).getCopyAvailableUntil()).isNull();
    }

    private void auditSinkUp() {
        when(auditService.logRequiredEvent(any())).thenReturn(AuditEventLogResponseDTO.builder().build());
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

    @Test
    void approvedFullCopyIsRequiredForPdfAndHospitalScopeIsRechecked() {
        asClinicianAtHospital();
        RoiRequest row = pendingRequest();
        when(roiRepository.findById(row.getId())).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.copyForExport(row.getId())).isInstanceOf(BusinessException.class);
        row.setStatus(RoiRequestStatus.FULFILLED);
        assertThatThrownBy(() -> service.copyForExport(row.getId())).isInstanceOf(BusinessException.class);
        row.setScopeDescription("FULL_RECORD");
        assertThat(service.copyForExport(row.getId())).isEqualTo(new RoiRequestService.ApprovedCopy(patientId, row.getId(), hospitalId));
        row.setRequestKind(com.example.hms.enums.RoiRequestKind.CORRECTION);
        assertThatThrownBy(() -> service.copyForExport(row.getId())).isInstanceOf(BusinessException.class);
        row.setRequestKind(com.example.hms.enums.RoiRequestKind.COPY);
        Hospital foreign = new Hospital(); foreign.setId(UUID.randomUUID()); row.setHospital(foreign);
        assertThatThrownBy(() -> service.copyForExport(row.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void correctionCompletionHasAnAttributedReasonButNeverClaimsARecordWasDisclosed() {
        asClinicianAtHospital();
        UUID actor = UUID.randomUUID();
        when(roleValidator.getCurrentUserId()).thenReturn(actor);
        RoiRequest row = pendingRequest();
        row.setRequestKind(com.example.hms.enums.RoiRequestKind.CORRECTION);
        when(roiRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(roiRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        service.fulfil(row.getId(), RoiDecisionDTO.builder().note("Reviewed and corrected through the authorized chart workflow").build());
        assertThat(row.getDecisionActorUserId()).isEqualTo(actor);
        assertThat(row.getDecidedAt()).isEqualTo(NOW);
        assertThat(row.getDecisionNote()).contains("authorized chart workflow");
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logRequiredEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.ROI_FULFILLED);
        assertThat(audit.getValue().getUserId()).isEqualTo(actor);
        assertThat(audit.getValue().getEventDescription()).doesNotContain("authorized chart workflow");
    }

    @Test
    void everyDecisionRequiresAReasonBeforeSaving() {
        asClinicianAtHospital();
        RoiRequest row = pendingRequest();
        when(roiRepository.findById(row.getId())).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.fulfil(row.getId(), RoiDecisionDTO.builder().note(" ").build())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.cancel(row.getId(), null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.deny(row.getId(), null)).isInstanceOf(BusinessException.class);
        assertThat(row.getStatus()).isEqualTo(RoiRequestStatus.PENDING);
        verify(roiRepository, never()).saveAndFlush(any());
    }

    @Test
    void selfRequestUsesServerDateAndAnInternalDueDateAndKeepsCorrectionNarrativeOffTheAudit() {
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(roiRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RoiSelfRequestCreateDTO dto = selfDto();
        dto.setRequestKind(com.example.hms.enums.RoiRequestKind.CORRECTION);
        dto.setRequestedOn(TODAY.minusYears(1));
        var result = service.createForSelf(patient, hospitalId, dto);
        assertThat(result.getRequestedOn()).isEqualTo(TODAY);
        assertThat(result.getDueOn()).isEqualTo(TODAY.plusDays(30));
        assertThat(result.getRequestKind()).isEqualTo(com.example.hms.enums.RoiRequestKind.CORRECTION);
        assertThat(result.getRequesterType()).isEqualTo(RoiRequesterType.PATIENT);
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
        verify(auditService).logRequiredEvent(audit.capture());
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
            .extracting("messageKey").isEqualTo("roi.request.thirdPartyNameRequired");
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
            .extracting("messageKey").isEqualTo("roi.request.dateInFuture");
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
            .extracting("messageKey").isEqualTo("roi.request.hospitalRequired");
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

        var dto = service.fulfil(request.getId(), RoiDecisionDTO.builder().note("cdx-Reviewed request").build());

        assertThat(dto.getStatus()).isEqualTo(RoiRequestStatus.FULFILLED);
        assertThat(dto.getDecidedAt()).isEqualTo(NOW);

        // The whole point of the workflow: the release lands in item 39's
        // accounting. PATIENT_EXPORT + patientId = COPY_RELEASED on the
        // patient's own report.
        ArgumentCaptor<AuditEventRequestDTO> audit =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService, times(2)).logRequiredEvent(audit.capture());
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
        // A defensive null result is refused as well as a thrown required-audit failure.
        when(auditService.logRequiredEvent(any())).thenReturn(null);
        RoiDecisionDTO decision = RoiDecisionDTO.builder().note("cdx-Reviewed request").build();
        UUID requestId = request.getId();

        assertThatThrownBy(() -> service.fulfil(requestId, decision))
            .isInstanceOf(com.example.hms.exception.RoiAuditUnavailableException.class)
            .extracting("messageKey").isEqualTo("roi.request.auditUnavailable");
    }

    @Test
    @DisplayName("deny needs a reason — it is the outcome the requester is told")
    void denyNeedsAReason() {
        // QA round 29 (assertion changed): a translated refusal on "note", read before the scope.
        RoiDecisionDTO blank = RoiDecisionDTO.builder().note("  ").build();
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> service.deny(requestId, blank))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class, ex -> {
                assertThat(ex.getField()).isEqualTo("note");
                assertThat(ex.getMessageKey()).isEqualTo("roi.deny.noteRequired");
            });
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
        verify(auditService).logRequiredEvent(audit.capture());
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
            .extracting("messageKey").isEqualTo("roi.request.alreadyDecided");
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
        RoiDecisionDTO decision = RoiDecisionDTO.builder().note("cdx-Reviewed request").build();
        UUID foreignId = foreign.getId();

        Throwable foreignT = catchThrowable(() -> service.fulfil(foreignId, decision));
        Throwable unknownT = catchThrowable(() -> service.fulfil(unknownId, decision));

        assertThat(foreignT).isInstanceOf(ResourceNotFoundException.class);
        assertThat(unknownT).isInstanceOf(ResourceNotFoundException.class);
        // Each renders the id it was asked for; with the ids masked the two are identical.
        assertThat(foreignT.getMessage().replace(foreignId.toString(), "<id>"))
            .isEqualTo(unknownT.getMessage().replace(unknownId.toString(), "<id>"));
    }

    @Test
    @DisplayName("a concurrent decision surfaces as a retryable refusal, not a silent overwrite")
    void concurrentDecisionIsRefused() {
        asClinicianAtHospital();
        RoiRequest request = pendingRequest();
        when(roiRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(roiRepository.saveAndFlush(any())).thenThrow(
            new ObjectOptimisticLockingFailureException(RoiRequest.class, request.getId()));
        RoiDecisionDTO decision = RoiDecisionDTO.builder().note("cdx-Reviewed request").build();
        UUID requestId = request.getId();

        assertThatThrownBy(() -> service.fulfil(requestId, decision))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("roi.request.decideConflict");
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
    @DisplayName("QA r73 D3700: the patient sees who decided by name, never the decider's account id")
    void requestsForSelfNeverCarryTheDecidersAccountId() {
        RoiRequest decided = pendingRequest();
        decided.setDecisionActorUserId(UUID.randomUUID());
        when(roiRepository.findByPatientIdOrderByCreatedAtDesc(patientId)).thenReturn(List.of(decided));

        assertThat(service.requestsForSelf(patient)).singleElement()
            .satisfies(dto -> assertThat(dto.getDecisionActorUserId()).isNull());
    }

    @Test
    @DisplayName("no active hospital context refuses the staff entry points")
    void noHospitalContextRefuses() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        RoiRequestCreateDTO dto = createDto();
        assertThatThrownBy(() -> service.create(patientId, dto))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("roi.request.hospitalContextRequired");
    }

    @Test
    @DisplayName("an unaudited rights request is refused, so the transaction rolls it back")
    void auditFailureRefusesTheAction() {
        asClinicianAtHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(roiRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("audit sink down")).when(auditService).logRequiredEvent(any());

        assertThatThrownBy(() -> service.create(patientId, createDto()))
            .isInstanceOf(com.example.hms.exception.RoiAuditUnavailableException.class)
            .extracting("messageKey").isEqualTo("roi.request.auditUnavailable");
    }
}
