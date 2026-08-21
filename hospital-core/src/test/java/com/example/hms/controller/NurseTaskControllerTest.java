package com.example.hms.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.nurse.MarVerificationRequestDTO;
import com.example.hms.payload.dto.nurse.MarVerificationResponseDTO;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseDashboardSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffCreateRequestDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseOrderTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseVitalTaskResponseDTO;
import com.example.hms.service.NurseTaskService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class NurseTaskControllerTest {

    @Mock private NurseTaskService nurseTaskService;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private Authentication auth;

    private NurseTaskController controller;

    private static final UUID NURSE_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new NurseTaskController(nurseTaskService, authUtils);
        // Default authUtils stubs matching the most common call path
        lenient().doNothing().when(authUtils).requireAuth(auth);
        lenient().when(authUtils.resolveHospitalScope(auth, null, false)).thenReturn(HOSPITAL_ID);
        lenient().when(authUtils.resolveHospitalScope(auth, HOSPITAL_ID, false)).thenReturn(HOSPITAL_ID);
        lenient().when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(NURSE_ID));
        lenient().when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(false);
    }

    /* ═══════════════════════════════════════════════════════════════════
       GET /nurse/vitals/due
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void getDueVitalsDefaultWindowAndNoAssignee() {
        List<NurseVitalTaskResponseDTO> vitals = List.of(
            NurseVitalTaskResponseDTO.builder()
                .id(UUID.randomUUID()).patientName("Alice").type("Routine")
                .dueTime(LocalDateTime.now()).overdue(false).build()
        );
        when(nurseTaskService.getDueVitals(eq(null), eq(HOSPITAL_ID), any(Duration.class)))
            .thenReturn(vitals);

        ResponseEntity<List<NurseVitalTaskResponseDTO>> response =
            controller.getDueVitals(null, null, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @ParameterizedTest(name = "getDueVitals window={0}, assignee={1}")
    @MethodSource("dueVitalsWindowArgs")
    void getDueVitalsWithVariousWindowsAndAssignees(String window, String assignee, Duration expectedDuration) {
        if (expectedDuration != null) {
            when(nurseTaskService.getDueVitals(null, HOSPITAL_ID, expectedDuration))
                .thenReturn(List.of());
        } else {
            when(nurseTaskService.getDueVitals(eq(null), eq(HOSPITAL_ID), any(Duration.class)))
                .thenReturn(List.of());
        }

        ResponseEntity<List<NurseVitalTaskResponseDTO>> response =
            controller.getDueVitals(window, assignee, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    static Stream<Arguments> dueVitalsWindowArgs() {
        return Stream.of(
            Arguments.of("4h", null, null),
            Arguments.of("30m", null, null),
            Arguments.of("60", null, null),
            Arguments.of("invalid", null, null),
            Arguments.of(null, "all", null),
            Arguments.of("20h", null, Duration.ofHours(12)),
            Arguments.of("1m", null, Duration.ofMinutes(15))
        );
    }

    @Test
    void getDueVitalsWithAssigneeMe() {
        when(nurseTaskService.getDueVitals(eq(NURSE_ID), eq(HOSPITAL_ID), any(Duration.class)))
            .thenReturn(List.of());

        ResponseEntity<List<NurseVitalTaskResponseDTO>> response =
            controller.getDueVitals(null, " me ", null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getDueVitalsWithExplicitHospitalId() {
        UUID explicitHospital = UUID.randomUUID();
        when(authUtils.resolveHospitalScope(auth, explicitHospital, false)).thenReturn(explicitHospital);
        when(nurseTaskService.getDueVitals(eq(null), eq(explicitHospital), any(Duration.class)))
            .thenReturn(List.of());

        ResponseEntity<List<NurseVitalTaskResponseDTO>> response =
            controller.getDueVitals(null, null, explicitHospital, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /* ═══════════════════════════════════════════════════════════════════
       GET /nurse/medications/mar
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void getMedicationTasks() {
        List<NurseMedicationTaskResponseDTO> meds = List.of(
            NurseMedicationTaskResponseDTO.builder()
                .id(UUID.randomUUID()).patientName("Bob").medication("Aspirin")
                .dose("81 mg").route("PO").dueTime(LocalDateTime.now()).status("DUE").build()
        );
        when(nurseTaskService.getMedicationTasks(null, HOSPITAL_ID, null)).thenReturn(meds);

        ResponseEntity<List<NurseMedicationTaskResponseDTO>> response =
            controller.getMedicationTasks(null, null, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void getMedicationTasksWithStatusFilter() {
        when(nurseTaskService.getMedicationTasks(null, HOSPITAL_ID, "OVERDUE")).thenReturn(List.of());

        ResponseEntity<List<NurseMedicationTaskResponseDTO>> response =
            controller.getMedicationTasks(null, "OVERDUE", null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /* ═══════════════════════════════════════════════════════════════════
       PUT /nurse/medications/mar/{taskId}/administer
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void administerMedicationSuccess() {
        UUID taskId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");

        NurseMedicationTaskResponseDTO response = NurseMedicationTaskResponseDTO.builder()
            .id(taskId).status("GIVEN").medication("Med").dose("10 mg").route("PO")
            .dueTime(LocalDateTime.now()).patientName("Pat").build();
        when(nurseTaskService.recordMedicationAdministration(taskId, NURSE_ID, HOSPITAL_ID, request))
            .thenReturn(response);

        ResponseEntity<NurseMedicationTaskResponseDTO> result =
            controller.administerMedication(taskId, request, "me", null, auth);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getStatus()).isEqualTo("GIVEN");
    }

    @Test
    void administerMedicationFallsBackToAuthWhenAssigneeNull() {
        UUID taskId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");

        NurseMedicationTaskResponseDTO response = NurseMedicationTaskResponseDTO.builder()
            .id(taskId).status("GIVEN").medication("Med").dose("10 mg").route("PO")
            .dueTime(LocalDateTime.now()).patientName("Pat").build();
        when(nurseTaskService.recordMedicationAdministration(taskId, NURSE_ID, HOSPITAL_ID, request))
            .thenReturn(response);

        // No assignee specified → resolveAssignee returns null → fallback to authUtils.resolveUserId
        ResponseEntity<NurseMedicationTaskResponseDTO> result =
            controller.administerMedication(taskId, request, null, null, auth);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void administerMedicationThrowsWhenCannotResolveNurse() {
        UUID taskId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");

        // No assignee and resolveUserId returns empty
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.administerMedication(taskId, request, null, null, auth))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unable to resolve nurse assignment");
    }

    /* ═══════════════════════════════════════════════════════════════════
       POST /nurse/medications/mar/{taskId}/verify   (P1 #8)
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void verifyMedicationReturnsAllPassedWhenScansMatch() {
        UUID taskId = UUID.randomUUID();
        MarVerificationRequestDTO request = new MarVerificationRequestDTO();
        request.setPatientScanValue(UUID.randomUUID().toString());
        request.setMedicationScanValue("AMOX-500");
        request.setDoseScanValue("500 mg");
        request.setRouteScanValue("PO");

        MarVerificationResponseDTO response = MarVerificationResponseDTO.builder()
            .marId(taskId)
            .allPassed(true)
            .verifiedAt(LocalDateTime.now())
            .build();
        when(nurseTaskService.verifyMedicationAdministration(taskId, NURSE_ID, HOSPITAL_ID, request))
            .thenReturn(response);

        ResponseEntity<MarVerificationResponseDTO> result =
            controller.verifyMedication(taskId, request, "me", null, auth);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().isAllPassed()).isTrue();
        verify(nurseTaskService).verifyMedicationAdministration(taskId, NURSE_ID, HOSPITAL_ID, request);
    }

    @Test
    void verifyMedicationFallsBackToAuthWhenAssigneeNull() {
        UUID taskId = UUID.randomUUID();
        MarVerificationRequestDTO request = new MarVerificationRequestDTO();
        request.setPatientScanValue(UUID.randomUUID().toString());
        request.setMedicationScanValue("AMOX-500");
        request.setDoseScanValue("500 mg");
        request.setRouteScanValue("PO");

        MarVerificationResponseDTO response = MarVerificationResponseDTO.builder()
            .marId(taskId).allPassed(false).build();
        when(nurseTaskService.verifyMedicationAdministration(taskId, NURSE_ID, HOSPITAL_ID, request))
            .thenReturn(response);

        ResponseEntity<MarVerificationResponseDTO> result =
            controller.verifyMedication(taskId, request, null, null, auth);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().isAllPassed()).isFalse();
    }

    @Test
    void verifyMedicationThrowsWhenCannotResolveNurse() {
        UUID taskId = UUID.randomUUID();
        MarVerificationRequestDTO request = new MarVerificationRequestDTO();
        request.setPatientScanValue("p");
        request.setMedicationScanValue("m");
        request.setDoseScanValue("d");
        request.setRouteScanValue("r");

        when(authUtils.resolveUserId(auth)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.verifyMedication(taskId, request, null, null, auth))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unable to resolve nurse identity");
    }

    @Test
    void administerMedicationPropagatesOverrideRequiredError() {
        UUID taskId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");

        when(nurseTaskService.recordMedicationAdministration(taskId, NURSE_ID, HOSPITAL_ID, request))
            .thenThrow(new BusinessException("Five-rights check failed; override reason required."));

        assertThatThrownBy(() -> controller.administerMedication(taskId, request, "me", null, auth))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Five-rights");
    }

    /* ═══════════════════════════════════════════════════════════════════
       GET /nurse/orders
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void getOrdersDefault() {
        when(nurseTaskService.getOrderTasks(null, HOSPITAL_ID, null, 6)).thenReturn(List.of());

        ResponseEntity<List<NurseOrderTaskResponseDTO>> response =
            controller.getOrders(null, null, null, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getOrdersWithLimitClamp() {
        // limit=50 → clamped to 20
        when(nurseTaskService.getOrderTasks(null, HOSPITAL_ID, "stat", 20)).thenReturn(List.of());

        ResponseEntity<List<NurseOrderTaskResponseDTO>> response =
            controller.getOrders(null, "stat", 50, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getOrdersWithLimitClampMin() {
        // limit=0 → clamped to 1
        when(nurseTaskService.getOrderTasks(null, HOSPITAL_ID, null, 1)).thenReturn(List.of());

        ResponseEntity<List<NurseOrderTaskResponseDTO>> response =
            controller.getOrders(null, null, 0, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /* ═══════════════════════════════════════════════════════════════════
       GET /nurse/handoffs
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void getHandoffsDefault() {
        when(nurseTaskService.getHandoffSummaries(null, HOSPITAL_ID, 6, null)).thenReturn(List.of());

        ResponseEntity<List<NurseHandoffSummaryDTO>> response =
            controller.getHandoffs(null, null, null, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getHandoffsWithLimit() {
        when(nurseTaskService.getHandoffSummaries(NURSE_ID, HOSPITAL_ID, 3, null)).thenReturn(List.of());

        ResponseEntity<List<NurseHandoffSummaryDTO>> response =
            controller.getHandoffs("me", 3, null, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /* ═══════════════════════════════════════════════════════════════════
       PUT /nurse/handoffs/{handoffId}/complete
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void completeHandoffSuccess() {
        UUID handoffId = UUID.randomUUID();
        doNothing().when(nurseTaskService).completeHandoff(handoffId, NURSE_ID, HOSPITAL_ID);

        ResponseEntity<Void> response =
            controller.completeHandoff(handoffId, null, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // The completer is the authenticated user, not the assignee filter.
        verify(nurseTaskService).completeHandoff(handoffId, NURSE_ID, HOSPITAL_ID);
    }

    @Test
    void completeHandoffSwallowsNotFoundException() {
        UUID handoffId = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("Not found"))
            .when(nurseTaskService).completeHandoff(handoffId, NURSE_ID, HOSPITAL_ID);

        // Should not throw — controller catches ResourceNotFoundException
        ResponseEntity<Void> response =
            controller.completeHandoff(handoffId, null, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /* ═══════════════════════════════════════════════════════════════════
       POST /nurse/handoffs
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void createHandoffReturns201() {
        UUID patientId = UUID.randomUUID();
        NurseHandoffCreateRequestDTO request = NurseHandoffCreateRequestDTO.builder()
            .patientId(patientId)
            .direction("Shift change")
            .situation("Post-op day 1, stable.")
            .build();
        NurseHandoffSummaryDTO created = NurseHandoffSummaryDTO.builder()
            .id(UUID.randomUUID()).patientId(patientId).patientName("Alice Doe")
            .direction("Shift change").note("Post-op day 1, stable.")
            .status("PENDING").updatedAt(LocalDateTime.now()).build();
        when(nurseTaskService.createHandoff(NURSE_ID, HOSPITAL_ID, request)).thenReturn(created);

        ResponseEntity<NurseHandoffSummaryDTO> response =
            controller.createHandoff(request, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getPatientName()).isEqualTo("Alice Doe");
    }

    @Test
    void createHandoffRequiresResolvableUser() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.empty());
        NurseHandoffCreateRequestDTO request = NurseHandoffCreateRequestDTO.builder()
            .patientId(UUID.randomUUID()).direction("Shift change").build();

        assertThatThrownBy(() -> controller.createHandoff(request, null, auth))
            .isInstanceOf(BusinessException.class);
    }

    /* ═══════════════════════════════════════════════════════════════════
       GET /nurse/announcements
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void getAnnouncementsDefault() {
        List<NurseAnnouncementDTO> announcements = List.of(
            NurseAnnouncementDTO.builder()
                .id(UUID.randomUUID()).text("Safety huddle").category("SHIFT")
                .createdAt(LocalDateTime.now()).startsAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(6)).build()
        );
        when(nurseTaskService.getAnnouncements(HOSPITAL_ID, 5)).thenReturn(announcements);

        ResponseEntity<List<NurseAnnouncementDTO>> response =
            controller.getAnnouncements(null, null, auth, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void getAnnouncementsWithLimit() {
        when(nurseTaskService.getAnnouncements(HOSPITAL_ID, 10)).thenReturn(List.of());

        ResponseEntity<List<NurseAnnouncementDTO>> response =
            controller.getAnnouncements(10, null, auth, Locale.ENGLISH);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /* ═══════════════════════════════════════════════════════════════════
       GET /nurse/dashboard/summary
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void getDashboardSummary() {
        NurseDashboardSummaryDTO summary = NurseDashboardSummaryDTO.builder()
            .assignedPatients(5).vitalsDue(2).medicationsDue(3)
            .medicationsOverdue(1).ordersPending(4).handoffsPending(1)
            .announcements(2).build();
        when(nurseTaskService.getDashboardSummary(null, HOSPITAL_ID)).thenReturn(summary);

        ResponseEntity<NurseDashboardSummaryDTO> response =
            controller.getDashboardSummary(null, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getAssignedPatients()).isEqualTo(5);
    }

    @Test
    void getDashboardSummaryWithMeAssignee() {
        NurseDashboardSummaryDTO summary = NurseDashboardSummaryDTO.builder()
            .assignedPatients(1).build();
        when(nurseTaskService.getDashboardSummary(NURSE_ID, HOSPITAL_ID)).thenReturn(summary);

        ResponseEntity<NurseDashboardSummaryDTO> response =
            controller.getDashboardSummary("me", null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /* ═══════════════════════════════════════════════════════════════════
       Hospital scope validation
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void throwsWhenHospitalScopeNullAndNotSuperAdmin() {
        when(authUtils.resolveHospitalScope(auth, null, false)).thenReturn(null);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(false);

        assertThatThrownBy(() -> controller.getDueVitals(null, null, null, auth))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital context required");
    }

    @Test
    void superAdminAllowedWithNullHospitalScope() {
        when(authUtils.resolveHospitalScope(auth, null, false)).thenReturn(null);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(true);
        when(nurseTaskService.getDueVitals(eq(null), eq(null), any(Duration.class))).thenReturn(List.of());

        ResponseEntity<List<NurseVitalTaskResponseDTO>> response =
            controller.getDueVitals(null, null, null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /* ═══════════════════════════════════════════════════════════════════
       resolveAssignee edge cases
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void resolveAssigneeWithBlankString() {
        when(nurseTaskService.getDueVitals(eq(null), eq(HOSPITAL_ID), any(Duration.class)))
            .thenReturn(List.of());

        // blank assignee → returns null nurseId
        ResponseEntity<List<NurseVitalTaskResponseDTO>> response =
            controller.getDueVitals(null, "  ", null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resolveAssigneeMeButCannotResolveThrows() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getDueVitals(null, "me", null, auth))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unable to resolve user identity");
    }

    @Test
    void resolveAssigneeUnknownValueThrows() {
        // Random string — not "me", "all", or blank → throws BusinessException
        assertThatThrownBy(() -> controller.getDueVitals(null, "nurse123", null, auth))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Invalid assignee value");
    }
}
