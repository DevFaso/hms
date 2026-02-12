package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateResponseDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseOrderTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseVitalTaskResponseDTO;
import com.example.hms.service.impl.NurseTaskServiceImpl;
import com.example.hms.utility.MessageUtil;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

class NurseTaskServiceImplTest {

    private NurseDashboardService nurseDashboardService;
    private NurseTaskServiceImpl nurseTaskService;

    @BeforeEach
    void setUp() {
        nurseDashboardService = mock(NurseDashboardService.class);
        MessageUtil.setMessageSource(null);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        MessageUtil.setMessageSource(messageSource);
        nurseTaskService = new NurseTaskServiceImpl(nurseDashboardService);
    }

    @Test
    void getDueVitalsDeduplicatesPatientsAndAppliesNameSuffixes() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID alexId = UUID.randomUUID();
        UUID jamieId = UUID.randomUUID();
        UUID jamieDuplicateId = UUID.randomUUID();
        UUID unnamedId = UUID.randomUUID();

        List<PatientResponseDTO> patients = List.of(
            PatientResponseDTO.builder().id(alexId).displayName("Alex Smith").build(),
            PatientResponseDTO.builder().id(alexId).displayName("Alex Smith").build(),
            PatientResponseDTO.builder().id(jamieId).displayName("Jamie Doe").build(),
            PatientResponseDTO.builder().id(jamieDuplicateId).displayName("Jamie Doe").build(),
            PatientResponseDTO.builder()
                .id(unnamedId)
                .displayName(" ")
                .patientName(" ")
                .firstName(null)
                .lastName(null)
                .build()
        );

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(patients);

        List<NurseVitalTaskResponseDTO> vitals = nurseTaskService.getDueVitals(nurseId, hospitalId, Duration.ofMinutes(10));

        assertThat(vitals)
            .hasSize(4)
            .extracting(NurseVitalTaskResponseDTO::getPatientName)
            .containsExactly("Alex Smith", "Jamie Doe", "Jamie Doe #2", "Patient");

        assertThat(vitals)
            .extracting(NurseVitalTaskResponseDTO::getPatientId)
            .containsExactly(alexId, jamieId, jamieDuplicateId, unnamedId);

        verify(nurseDashboardService).getPatientsForNurse(nurseId, hospitalId, null);
        verifyNoMoreInteractions(nurseDashboardService);
    }

    @Test
    void getOrderTasksFallsBackToSyntheticPatientWhenNoAssignmentsFound() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of());
        when(nurseDashboardService.getPatientsForNurse(null, hospitalId, null)).thenReturn(List.of());

        List<NurseOrderTaskResponseDTO> tasks = nurseTaskService.getOrderTasks(nurseId, hospitalId, "stat", 50);

        assertThat(tasks)
            .hasSize(1)
            .allSatisfy(task -> {
                assertThat(task.getPatientName()).isEqualTo("Sample Patient");
                assertThat(task.getPriority()).isEqualTo("STAT");
            });

        verify(nurseDashboardService).getPatientsForNurse(nurseId, hospitalId, null);
        verify(nurseDashboardService).getPatientsForNurse(null, hospitalId, null);
        verifyNoMoreInteractions(nurseDashboardService);
    }

    @Test
    void getAnnouncementsClampsLimitAndAbbreviatesHospitalId() {
        UUID hospitalId = UUID.randomUUID();

        List<NurseAnnouncementDTO> announcements = nurseTaskService.getAnnouncements(hospitalId, 50);

        assertThat(announcements).hasSize(20);

        String expectedPrefix = hospitalId.toString().substring(0, 8).toUpperCase();
        List<String> texts = announcements.stream()
            .map(NurseAnnouncementDTO::getText)
            .toList();

        assertThat(texts.get(0)).contains(expectedPrefix);
        assertThat(texts.stream().filter(text -> text.contains(expectedPrefix)).count())
            .isEqualTo(announcements.size());
    }

    @Test
    void recordMedicationAdministrationNormalizesStatus() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        PatientResponseDTO patient = PatientResponseDTO.builder()
            .id(patientId)
            .displayName("Jordan Rivers")
            .build();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of(patient));

        UUID taskId = nurseTaskService.getMedicationTasks(nurseId, hospitalId, null).get(0).getId();

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("given");

        NurseMedicationTaskResponseDTO response = nurseTaskService.recordMedicationAdministration(taskId, nurseId, hospitalId, request);

        assertThat(response.getStatus()).isEqualTo("GIVEN");
        assertThat(response.getId()).isEqualTo(taskId);
        assertThat(response.getPatientId()).isEqualTo(patientId);

        verify(nurseDashboardService, times(2)).getPatientsForNurse(nurseId, hospitalId, null);
        verifyNoMoreInteractions(nurseDashboardService);
    }

    @Test
    void recordMedicationAdministrationRejectsUnsupportedStatus() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        PatientResponseDTO patient = PatientResponseDTO.builder()
            .id(UUID.randomUUID())
            .displayName("Taylor Green")
            .build();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of(patient));

        UUID taskId = nurseTaskService.getMedicationTasks(nurseId, hospitalId, null).get(0).getId();

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("unknown");

        assertThatThrownBy(() -> nurseTaskService.recordMedicationAdministration(taskId, nurseId, hospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unsupported medication administration status");
    }

    @Test
    void updateHandoffChecklistItemReturnsCompletionState() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        PatientResponseDTO patient = PatientResponseDTO.builder()
            .id(patientId)
            .displayName("Jordan Rivers")
            .build();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of(patient));

        UUID handoffId = nurseTaskService.getHandoffSummaries(nurseId, hospitalId, 6).get(0).getId();
        UUID taskId = UUID.randomUUID();

        NurseHandoffChecklistUpdateResponseDTO response = nurseTaskService.updateHandoffChecklistItem(
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
    void updateHandoffChecklistItemRequiresExistingHandoff() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        PatientResponseDTO patient = PatientResponseDTO.builder()
            .id(UUID.randomUUID())
            .displayName("Taylor Green")
            .build();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of(patient));

        UUID unknownHandoffId = UUID.randomUUID();

        assertThatThrownBy(() -> nurseTaskService.updateHandoffChecklistItem(
            unknownHandoffId,
            UUID.randomUUID(),
            nurseId,
            hospitalId,
            true
        ))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Handoff not found");
    }

    @Test
    void completeHandoffValidatesInputs() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        assertThatThrownBy(() -> nurseTaskService.completeHandoff(null, nurseId, hospitalId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Handoff identifier is required");

        assertThatThrownBy(() -> nurseTaskService.completeHandoff(UUID.randomUUID(), nurseId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital context required");
    }

    @Test
    void completeHandoffReturnsQuietlyWhenHandoffMissing() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of());
        when(nurseDashboardService.getPatientsForNurse(null, hospitalId, null)).thenReturn(List.of());

        UUID unknownId = UUID.randomUUID();

        assertThatCode(() -> nurseTaskService.completeHandoff(unknownId, nurseId, hospitalId)).doesNotThrowAnyException();

        verify(nurseDashboardService).getPatientsForNurse(nurseId, hospitalId, null);
        verify(nurseDashboardService).getPatientsForNurse(null, hospitalId, null);
        verifyNoMoreInteractions(nurseDashboardService);
    }

    @Test
    void completeHandoffRecognizesKnownHandoff() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        PatientResponseDTO patient = PatientResponseDTO.builder()
            .id(UUID.randomUUID())
            .displayName("Chris Palmer")
            .build();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of(patient));

    UUID knownHandoffId = nurseTaskService.getHandoffSummaries(nurseId, hospitalId, 3).get(0).getId();

        assertThatCode(() -> nurseTaskService.completeHandoff(knownHandoffId, nurseId, hospitalId)).doesNotThrowAnyException();

        verify(nurseDashboardService, atLeastOnce()).getPatientsForNurse(nurseId, hospitalId, null);
        verifyNoMoreInteractions(nurseDashboardService);
    }

    @Test
    void getMedicationTasksRespectsStatusFilterAndAlternatesDefaultStatuses() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of());
        when(nurseDashboardService.getPatientsForNurse(null, hospitalId, null)).thenReturn(List.of());

        List<NurseMedicationTaskResponseDTO> defaultTasks = nurseTaskService.getMedicationTasks(nurseId, hospitalId, null);
    assertThat(defaultTasks).hasSize(1);
    assertThat(defaultTasks.get(0).getStatus()).isEqualTo("DUE");

        List<NurseMedicationTaskResponseDTO> filteredTasks = nurseTaskService.getMedicationTasks(nurseId, hospitalId, " overdue ");
    assertThat(filteredTasks).hasSize(1);
    assertThat(filteredTasks.get(0).getStatus()).isEqualTo("OVERDUE");
    }
}
