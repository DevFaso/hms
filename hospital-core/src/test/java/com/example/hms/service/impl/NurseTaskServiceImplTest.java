package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Announcement;
import com.example.hms.model.Hospital;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseDashboardSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateResponseDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseOrderTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseVitalTaskResponseDTO;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AnnouncementRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.NursingNoteRepository;
import com.example.hms.repository.NursingTaskRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.NurseDashboardService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class NurseTaskServiceImplTest {

    @Mock private NurseDashboardService nurseDashboardService;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private MedicationAdministrationRecordRepository marRepository;
    @Mock private PatientVitalSignRepository vitalSignRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private AdmissionRepository admissionRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private NursingTaskRepository nursingTaskRepository;
    @Mock private NursingNoteRepository nursingNoteRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;

    private NurseTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new NurseTaskServiceImpl(
            nurseDashboardService, prescriptionRepository, marRepository,
            vitalSignRepository, announcementRepository, staffRepository, hospitalRepository,
            admissionRepository, patientRepository, nursingTaskRepository,
            nursingNoteRepository, notificationRepository, userRepository));

        // Default stubs so synthetic/fallback paths activate in existing tests
        lenient().when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(any(), any()))
            .thenReturn(Optional.empty());
        lenient().when(prescriptionRepository.findByPatient_IdAndHospital_Id(any(), any()))
            .thenReturn(List.of());
        lenient().when(prescriptionRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(marRepository.findByPatient_IdAndHospital_IdAndStatus(any(), any(), any()))
            .thenReturn(List.of());
        lenient().when(marRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(announcementRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());
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
        // Both patients have no recorded vitals, so both get the same overdue dueTime
        assertThat(vitals.get(0).getDueTime()).isEqualTo(vitals.get(1).getDueTime());
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
                .isEqualTo(fixedNow.minusMinutes(30)); // No recorded vitals → overdue since 30 min ago
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

    /* ════════════════════════════════════════════════════════════════════
       Real-data coverage: Vitals with recorded vital signs
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void getDueVitalsWithRecentVitalShowsDueNotOverdue() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Jane Doe", "Jane", "Doe")));

        // Last vitals recorded 1 hour ago — within the 4h overdue threshold
        PatientVitalSign vs = PatientVitalSign.builder().recordedAt(fixedNow.minusHours(1)).build();
        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(vs));

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            List<NurseVitalTaskResponseDTO> vitals = service.getDueVitals(nurseId, hospitalId, Duration.ofHours(2));

            assertThat(vitals).hasSize(1);
            NurseVitalTaskResponseDTO task = vitals.get(0);
            // dueTime = lastRecorded + window = fixedNow - 1h + 2h = fixedNow + 1h
            assertThat(task.getDueTime()).isEqualTo(fixedNow.plusHours(1));
            assertThat(task.isOverdue()).isFalse();
            assertThat(task.getType()).isEqualTo("Routine");
        }
    }

    @Test
    void getDueVitalsWithOldVitalShowsOverdue() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Bob Overdue", "Bob", "Overdue")));

        // Last vitals recorded 6 hours ago — beyond the 4h overdue threshold
        PatientVitalSign vs = PatientVitalSign.builder().recordedAt(fixedNow.minusHours(6)).build();
        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(vs));

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            List<NurseVitalTaskResponseDTO> vitals = service.getDueVitals(nurseId, hospitalId, Duration.ofHours(2));

            assertThat(vitals).hasSize(1);
            NurseVitalTaskResponseDTO task = vitals.get(0);
            // dueTime = lastRecorded + window = fixedNow - 6h + 2h = fixedNow - 4h -> overdue
            assertThat(task.isOverdue()).isTrue();
            assertThat(task.getType()).isEqualTo("Full Set");
        }
    }

    @Test
    void getDueVitalsDefaultWindowWhenNull() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(UUID.randomUUID(), "Pat", "Pat", "Test")));

        List<NurseVitalTaskResponseDTO> vitals = service.getDueVitals(nurseId, hospitalId, null);
        assertThat(vitals).isNotEmpty();
    }

    /* ════════════════════════════════════════════════════════════════════
       Real-data coverage: Medications from real prescriptions
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void getMedicationTasksFromRealPrescriptions() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Med Patient", "Med", "Patient")));

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(UUID.randomUUID());
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(rx.getMedicationName()).thenReturn("Amoxicillin");
        when(rx.getDosage()).thenReturn("500");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn("PO");
        when(rx.getCreatedAt()).thenReturn(fixedNow.minusHours(1));

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        when(marRepository.findByPatient_IdAndHospital_IdAndStatus(eq(patientId), eq(hospitalId), any()))
            .thenReturn(List.of());

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);

            assertThat(tasks).hasSize(1);
            NurseMedicationTaskResponseDTO task = tasks.get(0);
            assertThat(task.getMedication()).isEqualTo("Amoxicillin");
            assertThat(task.getDose()).isEqualTo("500 mg");
            assertThat(task.getRoute()).isEqualTo("PO");
            assertThat(task.getStatus()).isEqualTo("DUE");
        }
    }

    @Test
    void getMedicationTasksSkipsDraftPrescriptions() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Draft Pat", "Draft", "Pat")));

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.DRAFT);

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));

        // Should fall back to synthetic tasks since DRAFT is filtered out
        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);
        assertThat(tasks).isNotEmpty();
    }

    @Test
    void getMedicationTasksWithOverduePrescription() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Overdue Pat", "Overdue", "Pat")));

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(UUID.randomUUID());
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.TRANSMITTED);
        when(rx.getMedicationName()).thenReturn("Metformin");
        when(rx.getDosage()).thenReturn("1000");
        when(rx.getDoseUnit()).thenReturn(null); // dosage only, no unit
        when(rx.getRoute()).thenReturn(null); // defaults to PO
        when(rx.getCreatedAt()).thenReturn(fixedNow.minusHours(8)); // way past 4h window

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        when(marRepository.findByPatient_IdAndHospital_IdAndStatus(eq(patientId), eq(hospitalId), any()))
            .thenReturn(List.of());

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);

            assertThat(tasks).hasSize(1);
            assertThat(tasks.get(0).getStatus()).isEqualTo("OVERDUE");
            assertThat(tasks.get(0).getDose()).isEqualTo("1000"); // dosage only, no unit
            assertThat(tasks.get(0).getRoute()).isEqualTo("PO"); // null defaults to PO
        }
    }

    @Test
    void getMedicationTasksCompletedStatus() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID rxId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Done Pat", "Done", "Pat")));

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(rxId);
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(rx.getMedicationName()).thenReturn("Aspirin");
        when(rx.getDosage()).thenReturn(null);
        when(rx.getDoseUnit()).thenReturn(null);
        when(rx.getRoute()).thenReturn("IV");
        when(rx.getCreatedAt()).thenReturn(fixedNow.minusHours(1));

        // MAR record shows this prescription was already GIVEN
        MedicationAdministrationRecord mar = Mockito.mock(MedicationAdministrationRecord.class);
        Prescription marPrescription = Mockito.mock(Prescription.class);
        when(marPrescription.getId()).thenReturn(rxId);
        when(mar.getPrescription()).thenReturn(marPrescription);

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        when(marRepository.findByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId,
            MedicationAdministrationStatus.GIVEN))
            .thenReturn(List.of(mar));

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);

            assertThat(tasks).hasSize(1);
            assertThat(tasks.get(0).getStatus()).isEqualTo("COMPLETED");
            assertThat(tasks.get(0).getDose()).isEqualTo("See order"); // both null
        }
    }

    @Test
    void getMedicationTasksFiltersStatusOnRealPrescriptions() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Filter Pat", "Filter", "Pat")));

        Patient mockPatient = Mockito.mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = Mockito.mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        lenient().when(rx.getId()).thenReturn(UUID.randomUUID());
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        lenient().when(rx.getMedicationName()).thenReturn("Ibuprofen");
        lenient().when(rx.getDosage()).thenReturn("400");
        lenient().when(rx.getDoseUnit()).thenReturn("mg");
        lenient().when(rx.getRoute()).thenReturn("PO");
        when(rx.getCreatedAt()).thenReturn(fixedNow.minusHours(1)); // DUE status

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        when(marRepository.findByPatient_IdAndHospital_IdAndStatus(eq(patientId), eq(hospitalId), any()))
            .thenReturn(List.of());

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            // Filter for OVERDUE — the DUE prescription should not appear; synthetic fallback may add items
            List<NurseMedicationTaskResponseDTO> filtered = service.getMedicationTasks(nurseId, hospitalId, "OVERDUE");
            assertThat(filtered).noneMatch(t -> "Ibuprofen".equals(t.getMedication()));
        }
    }

    /* ════════════════════════════════════════════════════════════════════
       Real-data coverage: recordMedicationAdministration (real Rx path)
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void recordMedicationAdministrationWithRealPrescription() {
        UUID rxId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("John Doe");
        Hospital mockHospital = Mockito.mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(rxId);
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getMedicationName()).thenReturn("Ceftriaxone");
        when(rx.getDosage()).thenReturn("2");
        when(rx.getDoseUnit()).thenReturn("g");
        when(rx.getRoute()).thenReturn("IV");
        when(rx.getCreatedAt()).thenReturn(LocalDateTime.now().minusHours(1));

        when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(rx));

        Staff mockStaff = Mockito.mock(Staff.class);
        when(staffRepository.findByUserIdAndHospitalId(nurseId, hospitalId))
            .thenReturn(Optional.of(mockStaff));

        MedicationAdministrationRecord savedRecord = MedicationAdministrationRecord.builder()
            .prescription(rx)
            .patient(mockPatient)
            .hospital(mockHospital)
            .medicationName("Ceftriaxone")
            .dose("2 g")
            .route("IV")
            .scheduledTime(LocalDateTime.now())
            .administeredAt(LocalDateTime.now())
            .status(MedicationAdministrationStatus.GIVEN)
            .build();
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenReturn(savedRecord);

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");
        request.setNote("Administered on schedule");

        NurseMedicationTaskResponseDTO result = service.recordMedicationAdministration(rxId, nurseId, hospitalId, request);

        assertThat(result.getMedication()).isEqualTo("Ceftriaxone");
        assertThat(result.getStatus()).isEqualTo("GIVEN");
        verify(marRepository).save(any(MedicationAdministrationRecord.class));
        verify(staffRepository).findByUserIdAndHospitalId(nurseId, hospitalId);
    }

    @Test
    void recordMedicationAdministrationWithHeldStatus() {
        UUID rxId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("Held Patient");
        Hospital mockHospital = Mockito.mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(rxId);
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getMedicationName()).thenReturn("Warfarin");
        when(rx.getDosage()).thenReturn("5");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn(null); // triggers PO default
        when(rx.getCreatedAt()).thenReturn(null); // triggers now+1h fallback

        when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(rx));
        when(staffRepository.findByUserIdAndHospitalId(nurseId, hospitalId)).thenReturn(Optional.empty());

        MedicationAdministrationRecord savedRecord = MedicationAdministrationRecord.builder()
            .prescription(rx)
            .patient(mockPatient)
            .hospital(mockHospital)
            .medicationName("Warfarin")
            .dose("5 mg")
            .route("PO")
            .scheduledTime(LocalDateTime.now())
            .administeredAt(LocalDateTime.now())
            .status(MedicationAdministrationStatus.HELD)
            .build();
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenReturn(savedRecord);

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("HELD");
        request.setNote("INR too high");

        NurseMedicationTaskResponseDTO result = service.recordMedicationAdministration(rxId, nurseId, hospitalId, request);

        assertThat(result.getStatus()).isEqualTo("HELD");
        ArgumentCaptor<MedicationAdministrationRecord> captor = ArgumentCaptor.forClass(MedicationAdministrationRecord.class);
        verify(marRepository).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("INR too high");
    }

    @Test
    void recordMedicationAdministrationWithExistingMarRecord() {
        UUID marId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("Existing Patient Name");

        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);

        MedicationAdministrationRecord existingMar = Mockito.mock(MedicationAdministrationRecord.class);
        when(existingMar.getId()).thenReturn(marId);
        when(existingMar.getPatient()).thenReturn(mockPatient);
        when(existingMar.getHospital()).thenReturn(mockHospital);
        when(existingMar.getMedicationName()).thenReturn("Existing Med");
        when(existingMar.getDose()).thenReturn("100 mg");
        when(existingMar.getRoute()).thenReturn("PO");
        when(existingMar.getScheduledTime()).thenReturn(LocalDateTime.now());

        when(prescriptionRepository.findById(marId)).thenReturn(Optional.empty());
        when(marRepository.findById(marId)).thenReturn(Optional.of(existingMar));
        when(marRepository.save(existingMar)).thenReturn(existingMar);

        Staff mockStaff = Mockito.mock(Staff.class);
        when(staffRepository.findByUserIdAndHospitalId(nurseId, hospitalId))
            .thenReturn(Optional.of(mockStaff));

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("REFUSED");
        request.setNote("Patient refused medication");

        NurseMedicationTaskResponseDTO result = service.recordMedicationAdministration(marId, nurseId, hospitalId, request);

        assertThat(result.getId()).isEqualTo(marId);
        assertThat(result.getStatus()).isEqualTo("REFUSED");
        assertThat(result.getPatientName()).isEqualTo("Existing Patient Name");
        verify(existingMar).setStatus(MedicationAdministrationStatus.REFUSED);
        verify(existingMar).setReason("Patient refused medication");
        verify(existingMar).setAdministeredByStaff(mockStaff);
    }

    @Test
    void recordMedicationAdministrationRequiresTaskId() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        assertThatThrownBy(() -> service.recordMedicationAdministration(null, nurseId, hospitalId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Medication task identifier is required");
    }

    @Test
    void recordMedicationAdministrationRejectsCrossHospitalPrescription() {
        UUID rxId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID scopedHospitalId = UUID.randomUUID();
        UUID otherHospitalId = UUID.randomUUID();

        Hospital otherHospital = Mockito.mock(Hospital.class);
        when(otherHospital.getId()).thenReturn(otherHospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getHospital()).thenReturn(otherHospital);
        when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(rx));

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");

        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, scopedHospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not belong to the scoped hospital");
    }

    @Test
    void recordMedicationAdministrationRejectsCrossHospitalExistingMar() {
        UUID marId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID scopedHospitalId = UUID.randomUUID();
        UUID otherHospitalId = UUID.randomUUID();

        Hospital otherHospital = Mockito.mock(Hospital.class);
        when(otherHospital.getId()).thenReturn(otherHospitalId);

        MedicationAdministrationRecord existingMar = Mockito.mock(MedicationAdministrationRecord.class);
        when(existingMar.getHospital()).thenReturn(otherHospital);

        when(prescriptionRepository.findById(marId)).thenReturn(Optional.empty());
        when(marRepository.findById(marId)).thenReturn(Optional.of(existingMar));

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");

        assertThatThrownBy(() -> service.recordMedicationAdministration(marId, nurseId, scopedHospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not belong to the scoped hospital");
    }

    @Test
    void recordMedicationAdministrationWithNullRequestDoesNotNPE() {
        UUID taskId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        List<NurseMedicationTaskResponseDTO> tasks = List.of(
            NurseMedicationTaskResponseDTO.builder()
                .id(taskId).patientId(UUID.randomUUID()).patientName("Demo")
                .medication("TestMed").dose("10 mg").route("IV")
                .dueTime(LocalDateTime.now()).status("DUE").build()
        );
        doReturn(tasks).when(service).getMedicationTasks(nurseId, hospitalId, null);

        NurseMedicationTaskResponseDTO result = service.recordMedicationAdministration(taskId, nurseId, hospitalId, null);

        assertThat(result.getStatus()).isEqualTo("GIVEN");
    }

    @Test
    void recordMedicationAdministrationExistingMarHeldSetsReasonFromNote() {
        UUID marId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("Held Patient");
        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);

        MedicationAdministrationRecord existingMar = Mockito.mock(MedicationAdministrationRecord.class);
        when(existingMar.getId()).thenReturn(marId);
        when(existingMar.getPatient()).thenReturn(mockPatient);
        when(existingMar.getHospital()).thenReturn(mockHospital);
        when(existingMar.getMedicationName()).thenReturn("Warfarin");
        when(existingMar.getDose()).thenReturn("5 mg");
        when(existingMar.getRoute()).thenReturn("PO");
        when(existingMar.getScheduledTime()).thenReturn(LocalDateTime.now());

        when(prescriptionRepository.findById(marId)).thenReturn(Optional.empty());
        when(marRepository.findById(marId)).thenReturn(Optional.of(existingMar));
        when(marRepository.save(existingMar)).thenReturn(existingMar);

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("HELD");
        request.setNote("INR too high");

        NurseMedicationTaskResponseDTO result = service.recordMedicationAdministration(marId, nurseId, hospitalId, request);

        assertThat(result.getStatus()).isEqualTo("HELD");
        assertThat(result.getPatientName()).isEqualTo("Held Patient");
        verify(existingMar).setReason("INR too high");
        verify(existingMar).setNotes("INR too high");
        verify(existingMar).setStatus(MedicationAdministrationStatus.HELD);
    }

    @Test
    void recordMedicationAdministrationWithBlankStatus() {
        UUID taskId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        List<NurseMedicationTaskResponseDTO> tasks = List.of(
            NurseMedicationTaskResponseDTO.builder()
                .id(taskId).patientId(UUID.randomUUID()).patientName("Demo")
                .medication("TestMed").dose("10 mg").route("IV")
                .dueTime(LocalDateTime.now()).status("DUE").build()
        );
        doReturn(tasks).when(service).getMedicationTasks(nurseId, hospitalId, null);

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("  "); // blank → defaults to GIVEN

        NurseMedicationTaskResponseDTO result = service.recordMedicationAdministration(taskId, nurseId, hospitalId, request);
        assertThat(result.getStatus()).isEqualTo("GIVEN");
    }

    /* ════════════════════════════════════════════════════════════════════
       Real-data coverage: Announcements from DB
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void getAnnouncementsReturnsDbAnnouncementsWhenPresent() {
        UUID hospitalId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        Announcement a1 = Announcement.builder().id(UUID.randomUUID()).text("Code Blue drill at 14:00").date(now.minusMinutes(30)).build();
        Announcement a2 = Announcement.builder().id(UUID.randomUUID()).text("PPE supply update").date(now.minusHours(1)).build();

        when(announcementRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(a1, a2)));

        List<NurseAnnouncementDTO> result = service.getAnnouncements(hospitalId, 5);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("Code Blue drill at 14:00");
        assertThat(result.get(0).getCategory()).isEqualTo("SHIFT");
        assertThat(result.get(1).getExpiresAt()).isNotNull();
    }

    /* ════════════════════════════════════════════════════════════════════
       Real-data coverage: Dashboard Summary
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void getDashboardSummaryAggregatesRealData() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Summary Pat", "Summary", "Pat")));

        // Patient has no vitals → should count as vitalsDue=1
        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());

        // One active prescription that's DUE
        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        lenient().when(rx.getId()).thenReturn(UUID.randomUUID());
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(rx.getCreatedAt()).thenReturn(fixedNow.minusHours(1));

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        when(marRepository.findByPatient_IdAndHospital_IdAndStatus(eq(patientId), eq(hospitalId), any()))
            .thenReturn(List.of());
        when(announcementRepository.count()).thenReturn(3L);

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            // Stub handoff/order synthetic generators to avoid LocalDate.atStartOfDay() NPE under MockedStatic
            doReturn(List.of()).when(service).getHandoffSummaries(nurseId, hospitalId, 20);
            doReturn(List.of()).when(service).getOrderTasks(nurseId, hospitalId, null, 20);

            NurseDashboardSummaryDTO summary = service.getDashboardSummary(nurseId, hospitalId);

            assertThat(summary.getAssignedPatients()).isEqualTo(1);
            assertThat(summary.getVitalsDue()).isEqualTo(1);
            assertThat(summary.getMedicationsDue()).isEqualTo(1);
            assertThat(summary.getMedicationsOverdue()).isZero();
            assertThat(summary.getAnnouncements()).isEqualTo(3);
        }
    }

    @Test
    void getDashboardSummaryWithOverdueMedications() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Multi Pat", "Multi", "Pat")));

        // Vitals recorded recently — not overdue
        PatientVitalSign vs = PatientVitalSign.builder().recordedAt(fixedNow.minusHours(1)).build();
        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(vs));

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription overdueRx = Mockito.mock(Prescription.class);
        lenient().when(overdueRx.getId()).thenReturn(UUID.randomUUID());
        when(overdueRx.getPatient()).thenReturn(mockPatient);
        when(overdueRx.getHospital()).thenReturn(mockHospital);
        when(overdueRx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(overdueRx.getCreatedAt()).thenReturn(fixedNow.minusHours(8)); // overdue

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(overdueRx));
        when(marRepository.findByPatient_IdAndHospital_IdAndStatus(eq(patientId), eq(hospitalId), any()))
            .thenReturn(List.of());
        when(announcementRepository.count()).thenReturn(0L);

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            // Stub handoff/order synthetic generators to avoid LocalDate.atStartOfDay() NPE under MockedStatic
            doReturn(List.of()).when(service).getHandoffSummaries(nurseId, hospitalId, 20);
            doReturn(List.of()).when(service).getOrderTasks(nurseId, hospitalId, null, 20);

            NurseDashboardSummaryDTO summary = service.getDashboardSummary(nurseId, hospitalId);

            assertThat(summary.getVitalsDue()).isZero();
            assertThat(summary.getMedicationsOverdue()).isEqualTo(1);
            assertThat(summary.getMedicationsDue()).isZero();
            assertThat(summary.getAnnouncements()).isEqualTo(6); // 0 count → default
        }
    }

    /* ════════════════════════════════════════════════════════════════════
       Edge case: resolveNurseStaff with null values
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void recordMedicationAdministrationWithNullNurseId() {
        UUID rxId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("NullNurse Pat");
        Hospital mockHospital = Mockito.mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(rxId);
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getMedicationName()).thenReturn("Aspirin");
        when(rx.getDosage()).thenReturn("81");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn("PO");
        when(rx.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(prescriptionRepository.findById(rxId)).thenReturn(Optional.of(rx));

        MedicationAdministrationRecord saved = MedicationAdministrationRecord.builder()
            .prescription(rx).patient(mockPatient)
            .hospital(mockHospital).medicationName("Aspirin").dose("81 mg")
            .route("PO").scheduledTime(LocalDateTime.now())
            .status(MedicationAdministrationStatus.GIVEN).build();
        when(marRepository.save(any())).thenReturn(saved);

        // nurseId is null → resolveNurseStaff returns empty, no staff set
        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");
        NurseMedicationTaskResponseDTO result = service.recordMedicationAdministration(rxId, null, hospitalId, request);
        assertThat(result.getStatus()).isEqualTo("GIVEN");
    }

    /* ════════════════════════════════════════════════════════════════════
       Uncomplete checklist item (completed=false)
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void updateHandoffChecklistItemWithCompletedFalse() {
        UUID handoffId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        doReturn(List.of(NurseHandoffSummaryDTO.builder().id(handoffId).updatedAt(LocalDateTime.now()).build()))
            .when(service).getHandoffSummaries(nurseId, hospitalId, 6);

        NurseHandoffChecklistUpdateResponseDTO response = service.updateHandoffChecklistItem(
            handoffId, taskId, nurseId, hospitalId, false
        );

        assertThat(response.isCompleted()).isFalse();
        assertThat(response.getCompletedAt()).isNull();
    }

    /* ════════════════════════════════════════════════════════════════════
       Edge case: getMedicationTasks with null createdAt on prescription
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void getMedicationTasksHandlesNullCreatedAt() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "NullDate Pat", "NullDate", "Pat")));

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(UUID.randomUUID());
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(rx.getMedicationName()).thenReturn("NullCreated");
        when(rx.getDosage()).thenReturn("10");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn("PO");
        when(rx.getCreatedAt()).thenReturn(null); // null createdAt

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        when(marRepository.findByPatient_IdAndHospital_IdAndStatus(eq(patientId), eq(hospitalId), any()))
            .thenReturn(List.of());

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);

            assertThat(tasks).hasSize(1);
            // null createdAt → computeMedicationDueTime returns now.plusHours(1)
            assertThat(tasks.get(0).getDueTime()).isEqualTo(fixedNow.plusHours(1));
        }
    }

    /* ════════════════════════════════════════════════════════════════════
       Edge case: getDueVitals with vitals exactly at threshold
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void getDueVitalsWithVitalsExactlyAtThresholdShowsDue() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Threshold Pat", "Thresh", "Old")));

        // Exactly 4h ago → dueTime = now-4h+2h = now-2h → overdue=true
        PatientVitalSign vs = PatientVitalSign.builder().recordedAt(fixedNow.minusHours(4)).build();
        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(vs));

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            List<NurseVitalTaskResponseDTO> vitals = service.getDueVitals(nurseId, hospitalId, Duration.ofHours(2));

            assertThat(vitals).hasSize(1);
            assertThat(vitals.get(0).isOverdue()).isTrue();
        }
    }
}
