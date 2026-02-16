package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateResponseDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseOrderTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseVitalTaskResponseDTO;
import com.example.hms.service.NurseDashboardService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NurseTaskServiceImplTest {

    @Mock
    private NurseDashboardService nurseDashboardService;

    private NurseTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new NurseTaskServiceImpl(nurseDashboardService));
    }

    @Test
    void getDueVitalsClampsLowerWindowAndDeduplicatesPatients() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID sharedId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        List<PatientResponseDTO> patients = List.of(
            patient(sharedId, "", "Ana", "Smith"),         // resolved name: Ana Smith
            patient(sharedId, "Display", "Different", "Name"), // duplicate id should be skipped
            patient(secondId, "", "Ana", "Smith")           // same name -> should be suffixed
        );

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(patients);

        List<NurseVitalTaskResponseDTO> vitals = service.getDueVitals(nurseId, hospitalId, Duration.ofMinutes(5));

        assertThat(vitals)
            .hasSize(2)
            .extracting(NurseVitalTaskResponseDTO::getPatientName)
            .containsExactly("Ana Smith", "Ana Smith #2");
        assertThat(vitals.get(0).getDueTime()).isBefore(vitals.get(1).getDueTime());
    }

    @Test
    void getDueVitalsClampsUpperWindow() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 8, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(
            List.of(patient(UUID.randomUUID(), "Display", "First", "Last"))
        );

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            List<NurseVitalTaskResponseDTO> vitals = service.getDueVitals(nurseId, hospitalId, Duration.ofMinutes(1_000));

            assertThat(vitals)
                .singleElement()
                .extracting(NurseVitalTaskResponseDTO::getDueTime)
                .isEqualTo(fixedNow.plusMinutes(240)); // 480 min window -> 240 offset
        }
    }

    @Test
    void getMedicationTasksUsesStatusFilterWhenProvided() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(
            List.of(
                patient(UUID.randomUUID(), "One", "First", "Last"),
                patient(UUID.randomUUID(), "Two", "First", "Last")
            )
        );

        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, " held ");

        assertThat(tasks)
            .isNotEmpty()
            .allMatch(task -> "HELD".equals(task.getStatus()));
    }

    @Test
    void getMedicationTasksFallsBackToHospitalWideQuery() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of());
        when(nurseDashboardService.getPatientsForNurse(null, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Display", "First", "Last")));

        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);

        assertThat(tasks).isNotEmpty();
        verify(nurseDashboardService).getPatientsForNurse(null, hospitalId, null);
    }

    @Test
    void getOrderTasksAppliesPriorityFilterAndClamp() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(
            List.of(
                patient(UUID.randomUUID(), "One", "First", "Last"),
                patient(UUID.randomUUID(), "Two", "First", "Last"),
                patient(UUID.randomUUID(), "Three", "First", "Last"),
                patient(UUID.randomUUID(), "Four", "First", "Last"),
                patient(UUID.randomUUID(), "Five", "First", "Last")
            )
        );

        List<NurseOrderTaskResponseDTO> tasks = service.getOrderTasks(nurseId, hospitalId, " stat ", 50);

        assertThat(tasks)
            .isNotEmpty()
            .allMatch(task -> "STAT".equalsIgnoreCase(task.getPriority()));
    }

    @Test
    void getHandoffSummariesClampsLimit() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(
            List.of(patient(UUID.randomUUID(), "One", "First", "Last"),
                patient(UUID.randomUUID(), "Two", "First", "Last"))
        );

        List<NurseHandoffSummaryDTO> handoffs = service.getHandoffSummaries(nurseId, hospitalId, 0);

        assertThat(handoffs).hasSize(1);
    }

    @Test
    void getAnnouncementsUsesDefaultHospitalSeedWhenNull() {
        List<NurseAnnouncementDTO> announcements = service.getAnnouncements(null, 0);

        assertThat(announcements)
            .hasSize(1)
            .first()
            .extracting(NurseAnnouncementDTO::getText)
            .asString()
            .contains("[HOSPITAL]");
    }

    @Test
    void getAnnouncementsAbbreviatesHospitalId() {
        UUID hospitalId = UUID.fromString("12345678-1234-5678-1234-567812345678");

        List<NurseAnnouncementDTO> announcements = service.getAnnouncements(hospitalId, 2);

        assertThat(announcements)
            .hasSize(2)
            .allSatisfy(announcement -> assertThat(announcement.getText()).contains("[12345678]"));
    }

    @Test
    void completeHandoffRequiresIdentifiers() {
        UUID hospitalId = UUID.randomUUID();

        UUID randomId = UUID.randomUUID();
        assertThatThrownBy(() -> service.completeHandoff(null, randomId, hospitalId))
            .isInstanceOf(BusinessException.class);
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        assertThatThrownBy(() -> service.completeHandoff(id1, id2, null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void completeHandoffReturnsSilentlyWhenNotFound() {
        UUID handoffId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        doReturn(List.of(NurseHandoffSummaryDTO.builder().id(UUID.randomUUID()).build()))
            .when(service).getHandoffSummaries(nurseId, hospitalId, 6);

        service.completeHandoff(handoffId, nurseId, hospitalId);

        verify(service).getHandoffSummaries(nurseId, hospitalId, 6);
    }

    @Test
    void recordMedicationAdministrationDefaultsAndNormalizesStatus() {
        UUID taskId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        List<NurseMedicationTaskResponseDTO> tasks = List.of(
            NurseMedicationTaskResponseDTO.builder()
                .id(taskId)
                .patientId(UUID.randomUUID())
                .patientName("Demo")
                .medication("Lisinopril")
                .dose("10 mg")
                .route("IV")
                .dueTime(LocalDateTime.now())
                .status("DUE")
                .build()
        );
        doReturn(tasks).when(service).getMedicationTasks(nurseId, hospitalId, null);

        NurseMedicationTaskResponseDTO defaulted = service.recordMedicationAdministration(
            taskId,
            nurseId,
            hospitalId,
            null
        );
        assertThat(defaulted.getStatus()).isEqualTo("GIVEN");

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus(" held ");

        NurseMedicationTaskResponseDTO normalized = service.recordMedicationAdministration(
            taskId,
            nurseId,
            hospitalId,
            request
        );
        assertThat(normalized.getStatus()).isEqualTo("HELD");
    }

    @Test
    void recordMedicationAdministrationRejectsUnsupportedStatus() {
        UUID taskId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("unknown");

        assertThatThrownBy(() -> service.recordMedicationAdministration(taskId, nurseId, hospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unsupported medication administration status");
    }

    @Test
    void recordMedicationAdministrationThrowsWhenTaskMissing() {
        UUID taskId = UUID.randomUUID();

        doReturn(List.of(NurseMedicationTaskResponseDTO.builder().id(UUID.randomUUID()).build()))
            .when(service).getMedicationTasks(any(), any(), any());

        UUID randomId = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        assertThatThrownBy(() -> service.recordMedicationAdministration(taskId, randomId, id2, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateHandoffChecklistItemRequiresIdentifiers() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        UUID randomId = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateHandoffChecklistItem(null, randomId, nurseId, hospitalId, true))
            .isInstanceOf(BusinessException.class);
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateHandoffChecklistItem(id1, id2, nurseId, null, true))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void updateHandoffChecklistItemReturnsResponseWhenHandoffExists() {
        UUID handoffId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        doReturn(List.of(NurseHandoffSummaryDTO.builder().id(handoffId).updatedAt(LocalDateTime.now()).build()))
            .when(service).getHandoffSummaries(nurseId, hospitalId, 6);

        NurseHandoffChecklistUpdateResponseDTO response = service.updateHandoffChecklistItem(
            handoffId,
            taskId,
            nurseId,
            hospitalId,
            true
        );

        assertThat(response.getHandoffId()).isEqualTo(handoffId);
        assertThat(response.getTaskId()).isEqualTo(taskId);
        assertThat(response.isCompleted()).isTrue();
        assertThat(response.getCompletedAt()).isNotNull();
    }

    @Test
    void updateHandoffChecklistItemThrowsWhenHandoffMissing() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID handoffId = UUID.randomUUID();

        doReturn(List.of()).when(service).getHandoffSummaries(nurseId, hospitalId, 6);

        UUID randomId = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateHandoffChecklistItem(handoffId, randomId, nurseId, hospitalId, false))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateHandoffChecklistItemWrapsLookupErrors() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID handoffId = UUID.randomUUID();

        doThrow(new RuntimeException("boom")).when(service).getHandoffSummaries(nurseId, hospitalId, 6);

        UUID randomId = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateHandoffChecklistItem(handoffId, randomId, nurseId, hospitalId, false))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMedicationTasksGeneratesDefaultPatientsWhenHospitalMissing() {
        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(UUID.randomUUID(), null, null);

        assertThat(tasks).isNotEmpty();
    }

    @Test
    void resolvePatientNameFallsBackToFirstAndLast() {
        PatientResponseDTO patient = patient(UUID.randomUUID(), "", "First", "Last");
        when(nurseDashboardService.getPatientsForNurse(any(), any(), eq(null)))
            .thenReturn(List.of(patient));

        List<NurseVitalTaskResponseDTO> vitals = service.getDueVitals(UUID.randomUUID(), UUID.randomUUID(), null);

        assertThat(vitals)
            .isNotEmpty()
            .first()
            .extracting(NurseVitalTaskResponseDTO::getPatientName)
            .isEqualTo("First Last");
    }

    private PatientResponseDTO patient(UUID id, String displayName, String first, String last) {
        return PatientResponseDTO.builder()
            .id(id)
            .displayName(displayName)
            .patientName(displayName)
            .firstName(first)
            .lastName(last)
            .build();
    }
}
