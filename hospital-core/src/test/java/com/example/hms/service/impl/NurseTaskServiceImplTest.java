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

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.enums.NurseHandoffStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Admission;
import com.example.hms.model.Announcement;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Notification;
import com.example.hms.model.NurseHandoff;
import com.example.hms.model.NurseHandoffChecklistItem;
import com.example.hms.model.NursingNote;
import com.example.hms.model.NursingTask;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.nurse.NurseAdmissionSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteRequestDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteResponseDTO;
import com.example.hms.payload.dto.nurse.NurseDashboardSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseFlowBoardDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateResponseDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseInboxItemDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseOrderTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseTaskCompleteRequestDTO;
import com.example.hms.payload.dto.nurse.NurseTaskCreateRequestDTO;
import com.example.hms.payload.dto.nurse.NurseTaskItemDTO;
import com.example.hms.payload.dto.nurse.NurseVitalCaptureRequestDTO;
import com.example.hms.payload.dto.nurse.NurseVitalTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseWorkboardPatientDTO;
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
import com.example.hms.repository.NurseHandoffRepository;
import com.example.hms.repository.NurseHandoffChecklistItemRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ProcedureOrderRepository;
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
    @Mock private NurseHandoffRepository nurseHandoffRepository;
    @Mock private NurseHandoffChecklistItemRepository nurseHandoffChecklistItemRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private ImagingOrderRepository imagingOrderRepository;
    @Mock private ProcedureOrderRepository procedureOrderRepository;

    private NurseTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new NurseTaskServiceImpl(
            nurseDashboardService, prescriptionRepository, marRepository,
            vitalSignRepository, announcementRepository, staffRepository, hospitalRepository,
            admissionRepository, patientRepository, nursingTaskRepository,
            nursingNoteRepository, notificationRepository, userRepository,
            nurseHandoffRepository, nurseHandoffChecklistItemRepository,
            labOrderRepository, imagingOrderRepository, procedureOrderRepository));

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
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(
            List.of(patient(patientId, "One", "First", "Last"))
        );

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(UUID.randomUUID());
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(rx.getMedicationName()).thenReturn("Lisinopril");
        when(rx.getDosage()).thenReturn("10");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn("PO");
        when(rx.getCreatedAt()).thenReturn(fixedNow.minusHours(1));

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        when(marRepository.findByPatient_IdAndHospital_IdAndStatus(eq(patientId), eq(hospitalId), any()))
            .thenReturn(List.of());

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, " due ");

            assertThat(tasks)
                .isNotEmpty()
                .allMatch(task -> "DUE".equals(task.getStatus()));
        }
    }

    @Test
    void getMedicationTasksFallsBackToHospitalWideQuery() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of());
        when(nurseDashboardService.getPatientsForNurse(null, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Display", "First", "Last")));

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(UUID.randomUUID());
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(rx.getMedicationName()).thenReturn("Fallback Med");
        when(rx.getDosage()).thenReturn("10");
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

            assertThat(tasks).isNotEmpty();
            verify(nurseDashboardService).getPatientsForNurse(null, hospitalId, null);
        }
    }

    @Test
    void getOrderTasksAppliesPriorityFilterAndClamp() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(UUID.randomUUID());
        when(mockPatient.getFullName()).thenReturn("Order Patient");

        LabOrder statOrder = Mockito.mock(LabOrder.class);
        when(statOrder.getId()).thenReturn(UUID.randomUUID());
        when(statOrder.getPatient()).thenReturn(mockPatient);
        when(statOrder.getStatus()).thenReturn(LabOrderStatus.ORDERED);
        when(statOrder.getPriority()).thenReturn("STAT");
        when(statOrder.getCreatedAt()).thenReturn(LocalDateTime.now());

        LabOrder routineOrder = Mockito.mock(LabOrder.class);
        when(routineOrder.getId()).thenReturn(UUID.randomUUID());
        when(routineOrder.getPatient()).thenReturn(mockPatient);
        when(routineOrder.getStatus()).thenReturn(LabOrderStatus.ORDERED);
        when(routineOrder.getPriority()).thenReturn("ROUTINE");
        when(routineOrder.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(labOrderRepository.findByHospital_Id(hospitalId)).thenReturn(List.of(statOrder, routineOrder));
        when(imagingOrderRepository.findByHospital_IdOrderByOrderedAtDesc(hospitalId)).thenReturn(List.of());
        when(procedureOrderRepository.findByHospital_IdOrderByOrderedAtDesc(hospitalId)).thenReturn(List.of());

        List<NurseOrderTaskResponseDTO> tasks = service.getOrderTasks(nurseId, hospitalId, " stat ", 50);

        assertThat(tasks)
            .isNotEmpty()
            .allMatch(task -> "STAT".equalsIgnoreCase(task.getPriority()));
    }

    @Test
    void getHandoffSummariesClampsLimit() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        Patient p1 = Mockito.mock(Patient.class);
        when(p1.getId()).thenReturn(UUID.randomUUID());
        when(p1.getFullName()).thenReturn("One");
        Patient p2 = Mockito.mock(Patient.class);
        lenient().when(p2.getId()).thenReturn(UUID.randomUUID());
        lenient().when(p2.getFullName()).thenReturn("Two");

        NurseHandoff h1 = Mockito.mock(NurseHandoff.class);
        when(h1.getId()).thenReturn(UUID.randomUUID());
        when(h1.getPatient()).thenReturn(p1);
        when(h1.getCreatedAt()).thenReturn(LocalDateTime.now());
        NurseHandoff h2 = Mockito.mock(NurseHandoff.class);
        lenient().when(h2.getId()).thenReturn(UUID.randomUUID());
        lenient().when(h2.getPatient()).thenReturn(p2);
        lenient().when(h2.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(nurseHandoffRepository.findByHospitalAndStatus(hospitalId, NurseHandoffStatus.PENDING))
            .thenReturn(List.of(h1, h2));

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
    void completeHandoffThrowsWhenNotFound() {
        UUID handoffId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        assertThatThrownBy(() -> service.completeHandoff(handoffId, nurseId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Handoff not found");
    }

    @Test
    void recordMedicationAdministrationDefaultsAndNormalizesStatus() {
        UUID taskId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("Demo");
        Hospital mockHospital = Mockito.mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(taskId);
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getMedicationName()).thenReturn("Lisinopril");
        when(rx.getDosage()).thenReturn("10");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn("IV");
        when(rx.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(prescriptionRepository.findById(taskId)).thenReturn(Optional.of(rx));

        MedicationAdministrationRecord givenRecord = MedicationAdministrationRecord.builder()
            .prescription(rx).patient(mockPatient).hospital(mockHospital)
            .medicationName("Lisinopril").dose("10 mg").route("IV")
            .scheduledTime(LocalDateTime.now()).status(MedicationAdministrationStatus.GIVEN).build();
        MedicationAdministrationRecord heldRecord = MedicationAdministrationRecord.builder()
            .prescription(rx).patient(mockPatient).hospital(mockHospital)
            .medicationName("Lisinopril").dose("10 mg").route("IV")
            .scheduledTime(LocalDateTime.now()).status(MedicationAdministrationStatus.HELD).build();
        when(marRepository.save(any(MedicationAdministrationRecord.class)))
            .thenReturn(givenRecord).thenReturn(heldRecord);

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

        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);
        NurseHandoff handoff = Mockito.mock(NurseHandoff.class);
        when(handoff.getHospital()).thenReturn(mockHospital);
        when(nurseHandoffRepository.findById(handoffId)).thenReturn(Optional.of(handoff));

        NurseHandoffChecklistItem item = new NurseHandoffChecklistItem();
        item.setId(taskId);
        when(nurseHandoffChecklistItemRepository.findById(taskId)).thenReturn(Optional.of(item));

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

        UUID randomId = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateHandoffChecklistItem(handoffId, randomId, nurseId, hospitalId, false))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateHandoffChecklistItemWrapsLookupErrors() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID handoffId = UUID.randomUUID();

        when(nurseHandoffRepository.findById(handoffId)).thenThrow(new RuntimeException("boom"));

        UUID randomId = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateHandoffChecklistItem(handoffId, randomId, nurseId, hospitalId, false))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void getMedicationTasksReturnsEmptyWhenHospitalMissing() {
        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(UUID.randomUUID(), null, null);

        assertThat(tasks).isEmpty();
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

        // DRAFT is not in ACTIVE_RX_STATUSES, so it is excluded — no synthetic fallback
        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);
        assertThat(tasks).isEmpty();
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
        UUID patientId = UUID.randomUUID();

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("Demo");
        Hospital mockHospital = Mockito.mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(taskId);
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getMedicationName()).thenReturn("TestMed");
        when(rx.getDosage()).thenReturn("10");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn("IV");
        when(rx.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(prescriptionRepository.findById(taskId)).thenReturn(Optional.of(rx));

        MedicationAdministrationRecord saved = MedicationAdministrationRecord.builder()
            .prescription(rx).patient(mockPatient).hospital(mockHospital)
            .medicationName("TestMed").dose("10 mg").route("IV")
            .scheduledTime(LocalDateTime.now()).status(MedicationAdministrationStatus.GIVEN).build();
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenReturn(saved);

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
        UUID patientId = UUID.randomUUID();

        Patient mockPatient = Mockito.mock(Patient.class);
        when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("Demo");
        Hospital mockHospital = Mockito.mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = Mockito.mock(Prescription.class);
        when(rx.getId()).thenReturn(taskId);
        when(rx.getPatient()).thenReturn(mockPatient);
        when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getMedicationName()).thenReturn("TestMed");
        when(rx.getDosage()).thenReturn("10");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn("IV");
        when(rx.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(prescriptionRepository.findById(taskId)).thenReturn(Optional.of(rx));

        MedicationAdministrationRecord saved = MedicationAdministrationRecord.builder()
            .prescription(rx).patient(mockPatient).hospital(mockHospital)
            .medicationName("TestMed").dose("10 mg").route("IV")
            .scheduledTime(LocalDateTime.now()).status(MedicationAdministrationStatus.GIVEN).build();
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenReturn(saved);

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

            // Stub handoff repo and order queries for dashboard
            when(nurseHandoffRepository.findByHospitalAndStatus(hospitalId, NurseHandoffStatus.PENDING))
                .thenReturn(List.of());
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

            // Stub handoff repo and order queries for dashboard
            when(nurseHandoffRepository.findByHospitalAndStatus(hospitalId, NurseHandoffStatus.PENDING))
                .thenReturn(List.of());
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

        Hospital mockHospital = Mockito.mock(Hospital.class);
        when(mockHospital.getId()).thenReturn(hospitalId);
        NurseHandoff handoff = Mockito.mock(NurseHandoff.class);
        when(handoff.getHospital()).thenReturn(mockHospital);
        when(nurseHandoffRepository.findById(handoffId)).thenReturn(Optional.of(handoff));

        NurseHandoffChecklistItem item = new NurseHandoffChecklistItem();
        item.setId(taskId);
        item.setCompleted(true);
        item.setCompletedAt(LocalDateTime.now());
        when(nurseHandoffChecklistItemRepository.findById(taskId)).thenReturn(Optional.of(item));

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

    /* ════════════════════════════════════════════════════════════════════
       MVP-12: getWorkboard
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void getWorkboardReturnsEmptyWhenHospitalIdNull() {
        assertThat(service.getWorkboard(UUID.randomUUID(), null)).isEmpty();
    }

    @Test
    void getWorkboardBuildsCardsFromActiveAdmissions() {
        UUID hospitalId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Patient pat = Patient.builder().firstName("John").lastName("Doe").build();
        pat.setId(patientId);
        Department dept = Department.builder().name("Cardiology").build();
        User drUser = User.builder().firstName("Dr").lastName("Smith").build();
        Staff provider = Staff.builder().user(drUser).build();
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);

        Admission adm = new Admission();
        adm.setId(UUID.randomUUID());
        adm.setPatient(pat);
        adm.setHospital(hosp);
        adm.setDepartment(dept);
        adm.setAdmittingProvider(provider);
        adm.setAcuityLevel(AcuityLevel.LEVEL_3_MAJOR);
        adm.setRoomBed("201-A");
        adm.setAdmissionDateTime(LocalDateTime.now().minusDays(1));
        adm.setStatus(AdmissionStatus.ACTIVE);

        when(admissionRepository.findActiveAdmissionsByHospital(hospitalId)).thenReturn(List.of(adm));
        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of());

        List<NurseWorkboardPatientDTO> result = service.getWorkboard(nurseId, hospitalId);

        assertThat(result).hasSize(1);
        NurseWorkboardPatientDTO card = result.get(0);
        assertThat(card.getPatientId()).isEqualTo(patientId);
        assertThat(card.getPatientName()).isEqualTo("John Doe");
        assertThat(card.getRoomBed()).isEqualTo("201-A");
        assertThat(card.getDepartmentName()).isEqualTo("Cardiology");
        assertThat(card.getAttendingDoctor()).isEqualTo("Dr Smith");
        assertThat(card.isVitalsDue()).isTrue(); // no vitals recorded
        assertThat(card.getMedsDue()).isZero();
    }

    @Test
    void getWorkboardCountsMedsDueFromActivePrescriptions() {
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Patient pat = Patient.builder().firstName("Jane").lastName("Doe").build();
        pat.setId(patientId);
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);

        Admission adm = new Admission();
        adm.setId(UUID.randomUUID());
        adm.setPatient(pat);
        adm.setHospital(hosp);
        adm.setStatus(AdmissionStatus.ACTIVE);
        adm.setAdmissionDateTime(LocalDateTime.now().minusHours(5));

        Prescription activePending = Prescription.builder()
            .patient(pat)
            .hospital(hosp)
            .status(PrescriptionStatus.SIGNED)
            .build();
        activePending.setId(UUID.randomUUID());
        activePending.setCreatedAt(LocalDateTime.now().minusHours(6));

        when(admissionRepository.findActiveAdmissionsByHospital(hospitalId)).thenReturn(List.of(adm));
        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(PatientVitalSign.builder().recordedAt(LocalDateTime.now()).build()));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(activePending));
        when(marRepository.findByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId, MedicationAdministrationStatus.GIVEN))
            .thenReturn(List.of()); // not given yet

        List<NurseWorkboardPatientDTO> result = service.getWorkboard(UUID.randomUUID(), hospitalId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isVitalsDue()).isFalse(); // recent vitals
        assertThat(result.get(0).getMedsDue()).isEqualTo(1L); // one active pending med
    }

    /* ════════════════════════════════════════════════════════════════════
       MVP-12: getPatientFlow
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void getPatientFlowReturnsEmptyBoardWhenHospitalIdNull() {
        NurseFlowBoardDTO result = service.getPatientFlow(null, null);
        assertThat(result.getPending()).isEmpty();
        assertThat(result.getActive()).isEmpty();
        assertThat(result.getCritical()).isEmpty();
        assertThat(result.getAwaitingDischarge()).isEmpty();
    }

    @Test
    void getPatientFlowCategorizesByAcuityAndStatus() {
        UUID hospitalId = UUID.randomUUID();
        Patient p1 = Patient.builder().firstName("Critical").lastName("Pat").build();
        p1.setId(UUID.randomUUID());
        Patient p2 = Patient.builder().firstName("Normal").lastName("Pat").build();
        p2.setId(UUID.randomUUID());
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);

        Admission criticalAdm = new Admission();
        criticalAdm.setId(UUID.randomUUID());
        criticalAdm.setPatient(p1);
        criticalAdm.setHospital(hosp);
        criticalAdm.setAcuityLevel(AcuityLevel.LEVEL_5_CRITICAL);
        criticalAdm.setStatus(AdmissionStatus.ACTIVE);
        criticalAdm.setAdmissionDateTime(LocalDateTime.now().minusHours(2));

        Admission activeAdm = new Admission();
        activeAdm.setId(UUID.randomUUID());
        activeAdm.setPatient(p2);
        activeAdm.setHospital(hosp);
        activeAdm.setAcuityLevel(AcuityLevel.LEVEL_2_MODERATE);
        activeAdm.setStatus(AdmissionStatus.ACTIVE);
        activeAdm.setAdmissionDateTime(LocalDateTime.now().minusHours(1));

        Patient p3 = Patient.builder().firstName("Discharge").lastName("Pat").build();
        p3.setId(UUID.randomUUID());
        Admission dischargeAdm = new Admission();
        dischargeAdm.setId(UUID.randomUUID());
        dischargeAdm.setPatient(p3);
        dischargeAdm.setHospital(hosp);
        dischargeAdm.setAcuityLevel(AcuityLevel.LEVEL_1_MINIMAL);
        dischargeAdm.setStatus(AdmissionStatus.AWAITING_DISCHARGE);
        dischargeAdm.setAdmissionDateTime(LocalDateTime.now().minusDays(3));

        when(admissionRepository.findActiveAdmissionsByHospital(hospitalId))
            .thenReturn(List.of(criticalAdm, activeAdm));
        when(admissionRepository.findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(hospitalId, AdmissionStatus.AWAITING_DISCHARGE))
            .thenReturn(List.of(dischargeAdm));

        NurseFlowBoardDTO board = service.getPatientFlow(hospitalId, null);

        assertThat(board.getCritical()).hasSize(1);
        assertThat(board.getCritical().get(0).getPatientName()).isEqualTo("Critical Pat");
        assertThat(board.getActive()).hasSize(1);
        assertThat(board.getAwaitingDischarge()).hasSize(1);
    }

    @Test
    void getPatientFlowFiltersByDepartment() {
        UUID hospitalId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        Patient pat = Patient.builder().firstName("Dept").lastName("Pat").build();
        pat.setId(UUID.randomUUID());
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);

        Admission adm = new Admission();
        adm.setId(UUID.randomUUID());
        adm.setPatient(pat);
        adm.setHospital(hosp);
        adm.setAcuityLevel(AcuityLevel.LEVEL_2_MODERATE);
        adm.setStatus(AdmissionStatus.ACTIVE);
        adm.setAdmissionDateTime(LocalDateTime.now().minusHours(1));

        when(admissionRepository.findByDepartmentIdAndStatusOrderByAdmissionDateTimeDesc(deptId, AdmissionStatus.ACTIVE))
            .thenReturn(List.of(adm));
        when(admissionRepository.findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(hospitalId, AdmissionStatus.AWAITING_DISCHARGE))
            .thenReturn(List.of());

        NurseFlowBoardDTO board = service.getPatientFlow(hospitalId, deptId);

        assertThat(board.getActive()).hasSize(1);
        assertThat(board.getActive().get(0).getPatientName()).isEqualTo("Dept Pat");
    }

    /* ════════════════════════════════════════════════════════════════════
       MVP-12: captureVitals
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void captureVitalsThrowsWhenPatientIdNull() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        NurseVitalCaptureRequestDTO request = new NurseVitalCaptureRequestDTO();
        assertThatThrownBy(() -> service.captureVitals(null, nurseId, hospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Patient ID");
    }

    @Test
    void captureVitalsThrowsWhenHospitalIdNull() {
        UUID patientId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        NurseVitalCaptureRequestDTO request = new NurseVitalCaptureRequestDTO();
        assertThatThrownBy(() -> service.captureVitals(patientId, nurseId, null, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital context");
    }

    @Test
    void captureVitalsSavesNormalVitals() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();

        Patient pat = Patient.builder().build();
        pat.setId(patientId);
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hosp));
        when(staffRepository.findByUserIdAndHospitalId(nurseId, hospitalId)).thenReturn(Optional.empty());

        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder()
            .heartRateBpm(72)
            .spo2Percent(98)
            .temperatureCelsius(36.8)
            .systolicBpMmHg(120)
            .diastolicBpMmHg(80)
            .build();

        service.captureVitals(patientId, nurseId, hospitalId, req);

        ArgumentCaptor<PatientVitalSign> captor = ArgumentCaptor.forClass(PatientVitalSign.class);
        verify(vitalSignRepository).save(captor.capture());
        PatientVitalSign saved = captor.getValue();
        assertThat(saved.getHeartRateBpm()).isEqualTo(72);
        assertThat(saved.isClinicallySignificant()).isFalse();
    }

    @Test
    void captureVitalsFlagsClinicallySignificantWhenHeartRateHigh() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        Patient pat6 = Patient.builder().build();
        pat6.setId(patientId);
        Hospital hosp6 = Hospital.builder().build();
        hosp6.setId(hospitalId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(pat6));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hosp6));

        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder()
            .heartRateBpm(160) // > 150
            .build();

        service.captureVitals(patientId, UUID.randomUUID(), hospitalId, req);

        ArgumentCaptor<PatientVitalSign> captor = ArgumentCaptor.forClass(PatientVitalSign.class);
        verify(vitalSignRepository).save(captor.capture());
        assertThat(captor.getValue().isClinicallySignificant()).isTrue();
    }

    @Test
    void captureVitalsFlagsClinicallySignificantWhenSpO2Low() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        Patient pat7 = Patient.builder().build();
        pat7.setId(patientId);
        Hospital hosp7 = Hospital.builder().build();
        hosp7.setId(hospitalId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(pat7));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hosp7));

        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder()
            .spo2Percent(85) // < 90
            .build();

        service.captureVitals(patientId, UUID.randomUUID(), hospitalId, req);

        ArgumentCaptor<PatientVitalSign> captor = ArgumentCaptor.forClass(PatientVitalSign.class);
        verify(vitalSignRepository).save(captor.capture());
        assertThat(captor.getValue().isClinicallySignificant()).isTrue();
    }

    /* ════════════════════════════════════════════════════════════════════
       MVP-12: getPendingAdmissions
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void getPendingAdmissionsReturnsEmptyWhenHospitalIdNull() {
        assertThat(service.getPendingAdmissions(null, null)).isEmpty();
    }

    @Test
    void getPendingAdmissionsReturnsNewArrivalsAndAwaitingDischarge() {
        UUID hospitalId = UUID.randomUUID();
        Patient pat1 = Patient.builder().firstName("New").lastName("Arrival").build();
        pat1.setId(UUID.randomUUID());
        Patient pat2 = Patient.builder().firstName("Awaiting").lastName("Discharge").build();
        pat2.setId(UUID.randomUUID());
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);

        Admission newAdm = new Admission();
        newAdm.setId(UUID.randomUUID());
        newAdm.setPatient(pat1);
        newAdm.setHospital(hosp);
        newAdm.setStatus(AdmissionStatus.PENDING);
        newAdm.setAcuityLevel(AcuityLevel.LEVEL_2_MODERATE);
        newAdm.setAdmissionDateTime(LocalDateTime.now().minusMinutes(30));

        Admission dischAdm = new Admission();
        dischAdm.setId(UUID.randomUUID());
        dischAdm.setPatient(pat2);
        dischAdm.setHospital(hosp);
        dischAdm.setStatus(AdmissionStatus.AWAITING_DISCHARGE);
        dischAdm.setAdmissionDateTime(LocalDateTime.now().minusDays(2));

        when(admissionRepository.findActiveAdmissionsByHospital(hospitalId)).thenReturn(List.of(newAdm));
        when(admissionRepository.findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(hospitalId, AdmissionStatus.AWAITING_DISCHARGE))
            .thenReturn(List.of(dischAdm));

        List<NurseAdmissionSummaryDTO> result = service.getPendingAdmissions(hospitalId, null);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(NurseAdmissionSummaryDTO::getPatientName)
            .containsExactly("New Arrival", "Awaiting Discharge");
    }

    /* ════════════════════════════════════════════════════════════════════
       MVP-13: getNursingTaskBoard
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void getNursingTaskBoardReturnsEmptyWhenHospitalIdNull() {
        assertThat(service.getNursingTaskBoard(null, null)).isEmpty();
    }

    @Test
    void getNursingTaskBoardFiltersWithStatusFilter() {
        UUID hospitalId = UUID.randomUUID();

        Patient taskPat1 = Patient.builder().firstName("P").lastName("One").build();
        taskPat1.setId(UUID.randomUUID());
        Hospital taskHosp1 = Hospital.builder().build();
        taskHosp1.setId(hospitalId);
        NursingTask task = NursingTask.builder()
            .patient(taskPat1)
            .hospital(taskHosp1)
            .category("ASSESSMENT").description("Check vitals").priority("ROUTINE")
            .status("PENDING").dueAt(LocalDateTime.now().plusHours(1))
            .createdByName("Nurse Jane")
            .build();
        task.setId(UUID.randomUUID());

        when(nursingTaskRepository.findByHospital_IdAndStatusOrderByDueAtAsc(hospitalId, "PENDING"))
            .thenReturn(List.of(task));

        List<NurseTaskItemDTO> result = service.getNursingTaskBoard(hospitalId, "PENDING");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(result.get(0).getCategory()).isEqualTo("ASSESSMENT");
    }

    @Test
    void getNursingTaskBoardUsesDefaultExclusionWhenNoFilter() {
        UUID hospitalId = UUID.randomUUID();

        Patient taskPat2 = Patient.builder().firstName("P").lastName("Two").build();
        taskPat2.setId(UUID.randomUUID());
        Hospital taskHosp2 = Hospital.builder().build();
        taskHosp2.setId(hospitalId);
        NursingTask task = NursingTask.builder()
            .patient(taskPat2)
            .hospital(taskHosp2)
            .category("WOUND_CARE").description("Dressing change").priority("URGENT")
            .status("IN_PROGRESS").dueAt(LocalDateTime.now().minusMinutes(10))
            .createdByName("Nurse Bob")
            .build();
        task.setId(UUID.randomUUID());

        when(nursingTaskRepository.findByHospital_IdAndStatusNotOrderByDueAtAsc(hospitalId, "COMPLETED"))
            .thenReturn(List.of(task));

        List<NurseTaskItemDTO> result = service.getNursingTaskBoard(hospitalId, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPriority()).isEqualTo("URGENT");
    }

    /* ════════════════════════════════════════════════════════════════════
       MVP-13: createNursingTask
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void createNursingTaskSavesAndReturnsDTO() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);
        Patient pat = Patient.builder().firstName("Task").lastName("Pat").build();
        pat.setId(patientId);
        User nurse = User.builder().firstName("Jane").lastName("Nurse").build();
        nurse.setId(nurseId);

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hosp));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(pat));
        when(userRepository.findById(nurseId)).thenReturn(Optional.of(nurse));

        NurseTaskCreateRequestDTO req = new NurseTaskCreateRequestDTO();
        req.setPatientId(patientId);
        req.setCategory("assessment");
        req.setDescription("Neuro check q2h");
        req.setPriority("routine");
        req.setDueAt(LocalDateTime.now().plusHours(2));

        when(nursingTaskRepository.save(any(NursingTask.class))).thenAnswer(inv -> {
            NursingTask t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        NurseTaskItemDTO result = service.createNursingTask(nurseId, hospitalId, req);

        assertThat(result.getCategory()).isEqualTo("ASSESSMENT");
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getPatientName()).isEqualTo("Task Pat");
        assertThat(result.getCreatedByName()).isEqualTo("Jane Nurse");
    }

    @Test
    void createNursingTaskThrowsWhenHospitalNotFound() {
        UUID hospitalId = UUID.randomUUID();
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        NurseTaskCreateRequestDTO req = new NurseTaskCreateRequestDTO();
        req.setPatientId(UUID.randomUUID());
        req.setCategory("ASSESSMENT");
        req.setDescription("Test");

        UUID nurseUserId = UUID.randomUUID();
        assertThatThrownBy(() -> service.createNursingTask(nurseUserId, hospitalId, req))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /* ════════════════════════════════════════════════════════════════════
       MVP-13: completeNursingTask
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void completeNursingTaskSetsCompletedFields() {
        UUID taskId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        Patient pat = Patient.builder().firstName("P").lastName("Done").build();
        pat.setId(UUID.randomUUID());
        Hospital taskHosp = Hospital.builder().build();
        taskHosp.setId(hospitalId);
        NursingTask task = NursingTask.builder()
            .patient(pat)
            .hospital(taskHosp)
            .category("WOUND_CARE").description("Dressing change")
            .priority("ROUTINE").status("PENDING")
            .createdByName("Creator")
            .build();
        task.setId(taskId);

        User nurse = User.builder().firstName("Complete").lastName("Nurse").build();
        nurse.setId(nurseId);
        when(nursingTaskRepository.findByIdAndHospital_Id(taskId, hospitalId)).thenReturn(Optional.of(task));
        when(userRepository.findById(nurseId)).thenReturn(Optional.of(nurse));
        when(nursingTaskRepository.save(any(NursingTask.class))).thenAnswer(inv -> inv.getArgument(0));

        NurseTaskCompleteRequestDTO req = new NurseTaskCompleteRequestDTO();
        req.setCompletionNote("Done well");

        NurseTaskItemDTO result = service.completeNursingTask(taskId, nurseId, hospitalId, req);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getCompletedByName()).isEqualTo("Complete Nurse");
        assertThat(result.getCompletionNote()).isEqualTo("Done well");
    }

    @Test
    void completeNursingTaskThrowsWhenNotFound() {
        UUID taskId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        when(nursingTaskRepository.findByIdAndHospital_Id(taskId, hospitalId)).thenReturn(Optional.empty());

        UUID nurseId = UUID.randomUUID();
        assertThatThrownBy(() -> service.completeNursingTask(taskId, nurseId, hospitalId, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /* ════════════════════════════════════════════════════════════════════
       MVP-13: getNurseInboxItems
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void getNurseInboxItemsReturnsEmptyWhenUsernameBlank() {
        assertThat(service.getNurseInboxItems("", 10)).isEmpty();
        assertThat(service.getNurseInboxItems(null, 10)).isEmpty();
    }

    @Test
    void getNurseInboxItemsMapsNotifications() {
        UUID notifId = UUID.randomUUID();
        Notification n = Notification.builder()
            .id(notifId).message("New order").read(false)
            .createdAt(LocalDateTime.now().minusMinutes(5))
            .recipientUsername("nurse1")
            .build();

        when(notificationRepository.findByRecipientUsername(eq("nurse1"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(n)));

        List<NurseInboxItemDTO> result = service.getNurseInboxItems("nurse1", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessage()).isEqualTo("New order");
        assertThat(result.get(0).isRead()).isFalse();
    }

    /* ════════════════════════════════════════════════════════════════════
       MVP-13: markNurseInboxRead
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void markNurseInboxReadSetsReadFlag() {
        UUID itemId = UUID.randomUUID();
        Notification n = Notification.builder()
            .id(itemId).recipientUsername("nurse1").read(false).build();
        when(notificationRepository.findById(itemId)).thenReturn(Optional.of(n));

        service.markNurseInboxRead(itemId, "nurse1");

        assertThat(n.isRead()).isTrue();
        verify(notificationRepository).save(n);
    }

    @Test
    void markNurseInboxReadThrowsWhenNotOwnNotification() {
        UUID itemId = UUID.randomUUID();
        Notification n = Notification.builder()
            .id(itemId).recipientUsername("other_nurse").read(false).build();
        when(notificationRepository.findById(itemId)).thenReturn(Optional.of(n));

        assertThatThrownBy(() -> service.markNurseInboxRead(itemId, "nurse1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Access denied");
    }

    /* ════════════════════════════════════════════════════════════════════
       MVP-13: createCareNote
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void createCareNoteWithDARTemplate() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();

        Patient pat = Patient.builder().firstName("Note").lastName("Patient").build();
        pat.setId(patientId);
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);
        User author = User.builder().firstName("Jane").lastName("RN").username("jane.rn").build();
        author.setId(nurseId);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hosp));
        when(userRepository.findById(nurseId)).thenReturn(Optional.of(author));
        when(nursingNoteRepository.save(any(NursingNote.class))).thenAnswer(inv -> {
            NursingNote n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return n;
        });

        NurseCareNoteRequestDTO req = new NurseCareNoteRequestDTO();
        req.setTemplate("DAR");
        req.setNarrative("Patient resting comfortably");
        req.setDataPart("Vital signs stable");
        req.setActionPart("Repositioned patient");
        req.setResponsePart("Patient comfortable after repositioning");

        NurseCareNoteResponseDTO result = service.createCareNote(patientId, nurseId, hospitalId, req);

        assertThat(result.getTemplate()).isEqualTo("DAR");
        assertThat(result.getAuthorName()).isEqualTo("Jane RN");
        assertThat(result.getPatientName()).isEqualTo("Note Patient");
        assertThat(result.getSummary()).isNotBlank();
    }

    @Test
    void createCareNoteWithSOAPIETemplate() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();

        Patient pat = Patient.builder().firstName("Soapie").lastName("Pat").build();
        pat.setId(patientId);
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);
        User author = User.builder().username("soap_nurse").build(); // no first name → uses username
        author.setId(nurseId);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hosp));
        when(userRepository.findById(nurseId)).thenReturn(Optional.of(author));
        when(nursingNoteRepository.save(any(NursingNote.class))).thenAnswer(inv -> {
            NursingNote n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return n;
        });

        NurseCareNoteRequestDTO req = new NurseCareNoteRequestDTO();
        req.setTemplate("SOAPIE");
        req.setSubjective("Reports pain 6/10");
        req.setObjective("Guarding abdomen");
        req.setAssessment("Acute pain");
        req.setPlan("Administer analgesic");
        req.setImplementation("Morphine 4mg IV given");
        req.setEvaluation("Pain reduced to 3/10 after 30 min");

        NurseCareNoteResponseDTO result = service.createCareNote(patientId, nurseId, hospitalId, req);

        assertThat(result.getTemplate()).isEqualTo("SOAPIE");
        assertThat(result.getAuthorName()).isEqualTo("soap_nurse"); // fallback to username
    }

    @Test
    void createCareNoteThrowsWhenPatientNotFound() {
        UUID patientId = UUID.randomUUID();
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        NurseCareNoteRequestDTO req = new NurseCareNoteRequestDTO();
        req.setTemplate("DAR");

        UUID nurseUserId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        assertThatThrownBy(() -> service.createCareNote(patientId, nurseUserId, hospitalId, req))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
