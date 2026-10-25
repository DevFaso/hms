package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.FiveRightsStatus;
import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Announcement;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Notification;
import com.example.hms.model.NurseHandoff;
import com.example.hms.model.NursingNote;
import com.example.hms.model.NursingTask;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.enums.ProcedureUrgency;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.nurse.MarVerificationRequestDTO;
import com.example.hms.payload.dto.nurse.MarVerificationResponseDTO;
import com.example.hms.payload.dto.nurse.NurseAdmissionSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteRequestDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteResponseDTO;
import com.example.hms.payload.dto.nurse.NurseDashboardSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseFlowBoardDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffCreateRequestDTO;
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
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.NurseHandoffRepository;
import com.example.hms.repository.NursingNoteRepository;
import com.example.hms.repository.NursingTaskRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.NurseDashboardService;
import com.example.hms.service.emar.FiveRightsVerificationService;
import tools.jackson.databind.ObjectMapper;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
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
    @Mock private EncounterRepository encounterRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private NursingTaskRepository nursingTaskRepository;
    @Mock private NursingNoteRepository nursingNoteRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private NurseHandoffRepository nurseHandoffRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private ImagingOrderRepository imagingOrderRepository;
    @Mock private ProcedureOrderRepository procedureOrderRepository;

    @Mock private com.example.hms.utility.RoleValidator roleValidator;

    private NurseTaskServiceImpl service;
    /** Contains every id: the default "admitted" set for tests not about D220. */
    private static final java.util.Set<UUID> EVERY_PATIENT = new java.util.AbstractSet<>() {
        @Override public boolean contains(Object o) { return true; }
        @Override public java.util.Iterator<UUID> iterator() { return java.util.Collections.emptyIterator(); }
        @Override public int size() { return 0; }
    };
    /** The open stay every listed patient is in, for MAR tests not about D514/D702. */
    private static final Admission EVERY_PATIENTS_STAY = new Admission();
    private static final java.util.Map<UUID, Admission> EVERY_PATIENT_IN_A_STAY = new java.util.AbstractMap<>() {
        @Override public Admission get(Object o) { return EVERY_PATIENTS_STAY; }
        @Override public boolean containsKey(Object o) { return true; }
        @Override public java.util.Set<java.util.Map.Entry<UUID, Admission>> entrySet() { return java.util.Set.of(); }
    };
    @org.mockito.Mock
    private com.example.hms.service.vitals.News2EscalationService news2EscalationService;
    private final FiveRightsVerificationService fiveRightsService = new FiveRightsVerificationService();

    /**
     * Real, not mocked — same reasoning as fiveRightsService above. The three
     * methods NurseTaskServiceImpl calls on it are pure functions of the
     * Prescription, so these tests exercise the actual gate rather than
     * asserting that it gets called. Mocking it is how #515's reservation
     * transitions ended up with no test at all.
     */
    private final com.example.hms.service.pharmacy.PharmacistVerificationService
        pharmacistVerificationService =
            new com.example.hms.service.pharmacy.PharmacistVerificationService(
                null, null, null, java.time.Clock.systemUTC(),
                new com.example.hms.service.EndedStayOrderGuard(mock(com.example.hms.repository.AdmissionRepository.class)));
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private com.example.hms.repository.UserRoleHospitalAssignmentRepository assignmentRepository;

    @BeforeEach
    void setUp() {
        service = spy(new NurseTaskServiceImpl(
            nurseDashboardService, prescriptionRepository, marRepository,
            vitalSignRepository, announcementRepository, staffRepository, hospitalRepository,
            admissionRepository, encounterRepository, patientRepository, nursingTaskRepository,
            nursingNoteRepository, pharmacistVerificationService, notificationRepository, userRepository,
            nurseHandoffRepository, labOrderRepository, imagingOrderRepository, procedureOrderRepository,
            fiveRightsService, objectMapper, news2EscalationService,
            new com.example.hms.service.support.DischargedPatientGuard(admissionRepository, encounterRepository),
            assignmentRepository));

        // QA D220 narrowed the vitals rounds to admitted patients; the tests
        // below that are about windows, names and counts treat every listed
        // patient as admitted. The D220 tests call the real method.
        lenient().doReturn(EVERY_PATIENT).when(service).activelyAdmittedPatientIds(any());
        lenient().doReturn(EVERY_PATIENT_IN_A_STAY).when(service).activeStaysByPatient(any());

        // Default stubs so synthetic/fallback paths activate in existing tests
        lenient().when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(any(), any()))
            .thenReturn(Optional.empty());
        lenient().when(prescriptionRepository.findByPatient_IdAndHospital_Id(any(), any()))
            .thenReturn(List.of());
        lenient().when(prescriptionRepository.findByIdForUpdate(any())).thenReturn(Optional.empty());
        // The tenant-scoped lock behaves like the real query: the bare-id
        // stub, filtered to the acting hospital.
        lenient().when(prescriptionRepository.findByIdAndHospitalIdForUpdate(any(), any()))
            .thenAnswer(inv -> prescriptionRepository.findByIdForUpdate(inv.getArgument(0))
                .filter(p -> p.getHospital() != null
                    && inv.getArgument(1).equals(p.getHospital().getId())));
        lenient().when(marRepository.findStatusById(any()))
            .thenAnswer(inv -> marRepository.findById(inv.getArgument(0))
                .map(MedicationAdministrationRecord::getStatus));
        lenient().when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(any()))
            .thenReturn(List.of());
        lenient().when(marRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(announcementRepository.findByHospital_IdOrderByDateDesc(any(), any(Pageable.class)))
            .thenReturn(Page.empty());
        lenient().when(encounterRepository.findFirstByPatient_IdAndHospital_IdAndStatusOrderByEncounterDateDesc(
            any(), any(), any())).thenReturn(Optional.empty());
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
    void getMedicationTasksReturnsEmptyWhenNoRealMedicationTasksMatchStatus() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(
            List.of(
                patient(UUID.randomUUID(), "One", "First", "Last"),
                patient(UUID.randomUUID(), "Two", "First", "Last")
            )
        );

        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, " held ");

        assertThat(tasks).isEmpty();
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

        assertThat(tasks).isEmpty();
        verify(nurseDashboardService).getPatientsForNurse(null, hospitalId, null);
    }

    @Test
    void getOrderTasksDerivesFromRealOrdersScopedToAssignedPatients() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID assignedPatientId = UUID.randomUUID();
        UUID otherPatientId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(assignedPatientId, "Ann Assigned", "Ann", "Assigned")));

        Patient assigned = mock(Patient.class);
        when(assigned.getId()).thenReturn(assignedPatientId);
        when(assigned.getFullName()).thenReturn("Ann Assigned");
        Patient other = mock(Patient.class);
        when(other.getId()).thenReturn(otherPatientId);

        LabOrder statDraw = mock(LabOrder.class);
        when(statDraw.getId()).thenReturn(UUID.randomUUID());
        when(statDraw.getPatient()).thenReturn(assigned);
        when(statDraw.getPriority()).thenReturn(" stat ");
        when(statDraw.getOrderDatetime()).thenReturn(LocalDateTime.of(2026, 8, 20, 8, 0));
        LabOrder otherPatientsDraw = mock(LabOrder.class);
        when(otherPatientsDraw.getPatient()).thenReturn(other);
        when(labOrderRepository.findByHospital_IdAndStatusIn(
            eq(hospitalId), argThat(s -> s.contains(LabOrderStatus.ORDERED))))
            .thenReturn(List.of(statDraw, otherPatientsDraw));

        ProcedureOrder procedure = mock(ProcedureOrder.class);
        when(procedure.getId()).thenReturn(UUID.randomUUID());
        when(procedure.getPatient()).thenReturn(assigned);
        when(procedure.getUrgency()).thenReturn(ProcedureUrgency.URGENT);
        when(procedure.getScheduledDatetime()).thenReturn(LocalDateTime.of(2026, 8, 20, 10, 0));
        when(procedureOrderRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any()))
            .thenReturn(List.of(procedure));

        List<NurseOrderTaskResponseDTO> all = service.getOrderTasks(nurseId, hospitalId, null, 20);
        assertThat(all)
            .extracting(NurseOrderTaskResponseDTO::getOrderType)
            .containsExactly("LAB", "PROCEDURE"); // sorted by due time; other patient's order filtered
        assertThat(all.get(0).getPriority()).isEqualTo("STAT");
        assertThat(all.get(0).getPatientName()).isEqualTo("Ann Assigned");

        List<NurseOrderTaskResponseDTO> statOnly = service.getOrderTasks(nurseId, hospitalId, " stat ", 20);
        assertThat(statOnly)
            .singleElement()
            .extracting(NurseOrderTaskResponseDTO::getOrderType)
            .isEqualTo("LAB");
    }

    @Test
    void getHandoffSummariesReadsPendingRowsAndClampsLimit() {
        UUID hospitalId = UUID.randomUUID();

        Patient patientEntity = mock(Patient.class);
        when(patientEntity.getId()).thenReturn(UUID.randomUUID());
        when(patientEntity.getFullName()).thenReturn("Bea Ward");

        NurseHandoff first = NurseHandoff.builder()
            .patient(patientEntity)
            .direction("Shift change")
            .situation("Post-op day 1, afebrile.")
            .recommendation("Continue q4h vitals.")
            .createdByName("Nina Nurse")
            .build();
        NurseHandoff second = NurseHandoff.builder()
            .patient(patientEntity)
            .direction("Transfer to Radiology")
            .build();
        when(nurseHandoffRepository.findByHospital_IdAndStatusOrderByCreatedAtDesc(
            eq(hospitalId), eq("PENDING"), any(Pageable.class)))
            .thenReturn(List.of(first, second));

        // limit 0 clamps to 1 — nurseUserId null means no assignee scope
        List<NurseHandoffSummaryDTO> handoffs = service.getHandoffSummaries(null, null, hospitalId, 0, null);

        assertThat(handoffs).hasSize(1);
        NurseHandoffSummaryDTO dto = handoffs.get(0);
        assertThat(dto.getPatientName()).isEqualTo("Bea Ward");
        assertThat(dto.getDirection()).isEqualTo("Shift change");
        assertThat(dto.getNote()).isEqualTo("Post-op day 1, afebrile.");
        assertThat(dto.getRecommendation()).isEqualTo("Continue q4h vitals.");
        assertThat(dto.getCreatedByName()).isEqualTo("Nina Nurse");
        assertThat(dto.getStatus()).isEqualTo("PENDING");
    }

    /** QA D776: the recipient sees the handoff addressed to her even when the patient is not on her list. */
    @Test
    void getHandoffSummaries_includesAHandoffAddressedToMe_D776() {
        UUID hospitalId = UUID.randomUUID();
        UUID me = UUID.randomUUID();
        com.example.hms.payload.dto.PatientResponseDTO mine = new com.example.hms.payload.dto.PatientResponseDTO();
        mine.setId(UUID.randomUUID());
        when(nurseDashboardService.getPatientsForNurse(me, hospitalId, null)).thenReturn(List.of(mine));

        Patient other = mock(Patient.class);
        when(other.getId()).thenReturn(UUID.randomUUID());
        NurseHandoff toMe = NurseHandoff.builder().patient(other).direction("SHIFT_CHANGE")
            .createdByUserId(UUID.randomUUID()).recipientUserId(me).build();
        NurseHandoff notMine = NurseHandoff.builder().patient(other).direction("SHIFT_CHANGE")
            .createdByUserId(UUID.randomUUID()).recipientUserId(UUID.randomUUID()).build();
        when(nurseHandoffRepository.findByHospital_IdAndStatusOrderByCreatedAtDesc(
            eq(hospitalId), eq("PENDING"), any(Pageable.class)))
            .thenReturn(List.of(toMe, notMine));

        List<NurseHandoffSummaryDTO> handoffs = service.getHandoffSummaries(me, me, hospitalId, 20, null);

        assertThat(handoffs).singleElement().satisfies(dto -> {
            assertThat(dto.getRecipientUserId()).isEqualTo(me);
            assertThat(dto.getCompletableByMe()).isTrue();
        });
    }

    /**
     * QA D785 re-test: NURSE_A, the recipient of PENDING handoff e32e65ef,
     * got completableByMe=true with assignee=me but false from
     * GET /api/nurse/handoffs?hospitalId=A (no assignee): the flag was
     * computed for the list filter, which is null without assignee=me.
     */
    @Test
    void getHandoffSummaries_completableByMeFollowsTheCallerNotTheFilter_D785() {
        UUID hospitalId = UUID.randomUUID();
        UUID me = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(UUID.randomUUID());
        NurseHandoff toMe = NurseHandoff.builder().patient(patient).direction("SHIFT_CHANGE")
            .createdByUserId(author).recipientUserId(me).build();
        NurseHandoff toSomeoneElse = NurseHandoff.builder().patient(patient).direction("SHIFT_CHANGE")
            .createdByUserId(author).recipientUserId(UUID.randomUUID()).build();
        when(nurseHandoffRepository.findByHospital_IdAndStatusOrderByCreatedAtDesc(
            eq(hospitalId), eq("PENDING"), any(Pageable.class)))
            .thenReturn(List.of(toMe, toSomeoneElse));

        // hospitalId only: no assignee filter, the caller is the recipient
        List<NurseHandoffSummaryDTO> whole = service.getHandoffSummaries(null, me, hospitalId, 20, null);

        assertThat(whole).extracting(NurseHandoffSummaryDTO::getCompletableByMe).containsExactly(true, false);
        // the author and a bystander still read false on the same list
        assertThat(service.getHandoffSummaries(null, author, hospitalId, 20, null))
            .extracting(NurseHandoffSummaryDTO::getCompletableByMe).containsExactly(false, false);
        assertThat(service.getHandoffSummaries(null, UUID.randomUUID(), hospitalId, 20, null))
            .extracting(NurseHandoffSummaryDTO::getCompletableByMe).containsExactly(false, false);
    }

    @Test
    void getHandoffSummariesReadsCompletedRowsWithCompletionMetadata() {
        // ?status=COMPLETED shipped in PR #462; until this test nothing pinned
        // that the completion metadata actually rounds the trip.
        UUID hospitalId = UUID.randomUUID();

        Patient patientEntity = mock(Patient.class);
        when(patientEntity.getId()).thenReturn(UUID.randomUUID());
        when(patientEntity.getFullName()).thenReturn("Bea Ward");

        NurseHandoff done = NurseHandoff.builder()
            .patient(patientEntity)
            .direction("Shift change")
            .status("COMPLETED")
            .completedAt(java.time.LocalDateTime.of(2026, 8, 22, 7, 30))
            .completedByName("Nina Nurse")
            .build();
        when(nurseHandoffRepository.findByHospital_IdAndStatusOrderByCreatedAtDesc(
            eq(hospitalId), eq("COMPLETED"), any(Pageable.class)))
            .thenReturn(List.of(done));

        List<NurseHandoffSummaryDTO> handoffs =
            service.getHandoffSummaries(null, null, hospitalId, 20, "COMPLETED");

        assertThat(handoffs).singleElement().satisfies(dto -> {
            assertThat(dto.getStatus()).isEqualTo("COMPLETED");
            assertThat(dto.getCompletedAt())
                .isEqualTo(java.time.LocalDateTime.of(2026, 8, 22, 7, 30));
            assertThat(dto.getCompletedByName()).isEqualTo("Nina Nurse");
        });
    }

    @Test
    void getHandoffSummariesRefusesAnUnknownStatus() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.getHandoffSummaries(null, null, UUID.randomUUID(), 20, "ARCHIVED"))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasMessageContaining("ARCHIVED");
    }

    @Test
    void createHandoff_refusesAFreeTextDirection_D235() {
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(mock(Hospital.class)));
        Patient patientEntity = mock(Patient.class);
        when(patientEntity.isRegisteredInHospital(hospitalId)).thenReturn(true);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patientEntity));

        NurseHandoffCreateRequestDTO request = NurseHandoffCreateRequestDTO.builder()
            .patientId(patientId).direction("anything at all").build();

        UUID actor = UUID.randomUUID();
        assertThatThrownBy(() -> service.createHandoff(actor, hospitalId, request))
            .isInstanceOf(BusinessException.class);
        verify(nurseHandoffRepository, never()).save(any());
    }

    @Test
    void createHandoff_refusesAnEmptySituation_D331() {
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(mock(Hospital.class)));
        Patient patientEntity = mock(Patient.class);
        when(patientEntity.isRegisteredInHospital(hospitalId)).thenReturn(true);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patientEntity));

        NurseHandoffCreateRequestDTO request = NurseHandoffCreateRequestDTO.builder()
            .patientId(patientId).direction("SHIFT_CHANGE").situation("   ").build();

        UUID actor = UUID.randomUUID();
        assertThatThrownBy(() -> service.createHandoff(actor, hospitalId, request))
            .isInstanceOf(BusinessException.class);
        verify(nurseHandoffRepository, never()).save(any());
    }

    @Test
    void getDueVitals_skipsPatientsWithoutAnActiveStay_D220() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID admittedId = UUID.randomUUID();
        UUID outpatientId = UUID.randomUUID();
        doCallRealMethod().when(service).activelyAdmittedPatientIds(any());
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of(
            patient(admittedId, "In Bed", "In", "Bed"), patient(outpatientId, "Walk Out", "Walk", "Out")));
        Patient admittedPatient = new Patient();
        admittedPatient.setId(admittedId);
        Admission stay = new Admission();
        stay.setPatient(admittedPatient);
        when(admissionRepository.findActiveAdmissionsByHospital(hospitalId)).thenReturn(List.of(stay));

        List<NurseVitalTaskResponseDTO> vitals = service.getDueVitals(nurseId, hospitalId, null);

        assertThat(vitals).extracting(NurseVitalTaskResponseDTO::getPatientId).containsExactly(admittedId);
    }

    @Test
    void getDueVitals_highNews2ShortensTheRoundTo15Minutes_D207() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2026, 10, 19, 10, 0);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "A Three", "A", "Three")));
        // The QA set: T 38.9, HR 112, RR 26, BP 95/60, SpO2 91 -> NEWS2 HIGH.
        PatientVitalSign last = PatientVitalSign.builder()
            .recordedAt(fixedNow.minusMinutes(20))
            .temperatureCelsius(38.9).heartRateBpm(112).respiratoryRateBpm(26)
            .systolicBpMmHg(95).diastolicBpMmHg(60).spo2Percent(91)
            .build();
        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(last));

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);
            List<NurseVitalTaskResponseDTO> vitals = service.getDueVitals(nurseId, hospitalId, null);

            assertThat(vitals).singleElement().satisfies(v -> {
                assertThat(v.getType()).isEqualTo("NEWS2_HIGH");
                assertThat(v.getDueTime()).isEqualTo(fixedNow.minusMinutes(5));
                assertThat(v.isOverdue()).isTrue();
                assertThat(v.getNews2Score()).isGreaterThanOrEqualTo(7);
            });
        }
    }

    @Test
    void createHandoffPersistsSbarRecord() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Hospital hospital = mock(Hospital.class);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        Patient patientEntity = mock(Patient.class);
        when(patientEntity.isRegisteredInHospital(hospitalId)).thenReturn(true);
        when(patientEntity.getId()).thenReturn(patientId);
        when(patientEntity.getFullName()).thenReturn("Bea Ward");
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patientEntity));
        when(nurseHandoffRepository.save(any(NurseHandoff.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        UUID recipientId = UUID.randomUUID();
        when(assignmentRepository.existsActiveWitness(eq(recipientId), eq(hospitalId), any())).thenReturn(true);
        NurseHandoffCreateRequestDTO request = NurseHandoffCreateRequestDTO.builder()
            .patientId(patientId)
            .direction("  shift_change  ")
            .situation(" Stable overnight. ")
            .background("")
            .recipientUserId(recipientId)
            .build();

        NurseHandoffSummaryDTO created = service.createHandoff(nurseId, hospitalId, request);

        assertThat(created.getDirection()).isEqualTo("SHIFT_CHANGE");
        assertThat(created.getNote()).isEqualTo("Stable overnight.");
        assertThat(created.getBackground()).isNull(); // blank trimmed to null
        assertThat(created.getStatus()).isEqualTo("PENDING");
        assertThat(created.getPatientId()).isEqualTo(patientId);

        ArgumentCaptor<NurseHandoff> captor = ArgumentCaptor.forClass(NurseHandoff.class);
        verify(nurseHandoffRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        // QA D776: the handoff names its author and its recipient.
        assertThat(captor.getValue().getCreatedByUserId()).isEqualTo(nurseId);
        assertThat(captor.getValue().getRecipientUserId()).isEqualTo(recipientId);
        assertThat(created.getRecipientUserId()).isEqualTo(recipientId);
        assertThat(created.getCompletableByMe()).isFalse(); // the author cannot complete it
    }

    private NurseHandoffCreateRequestDTO validHandoff(UUID patientId, UUID recipientId) {
        return NurseHandoffCreateRequestDTO.builder()
            .patientId(patientId).direction("SHIFT_CHANGE").situation("Stable").recipientUserId(recipientId).build();
    }

    private UUID stubHandoffPatient(UUID hospitalId) {
        UUID patientId = UUID.randomUUID();
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(mock(Hospital.class)));
        Patient patientEntity = mock(Patient.class);
        when(patientEntity.isRegisteredInHospital(hospitalId)).thenReturn(true);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patientEntity));
        return patientId;
    }

    /** QA D776, the exact replay: POST /nurse/handoffs with no recipient was 201. */
    @Test
    void createHandoff_refusesNoRecipient_D776() {
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = stubHandoffPatient(hospitalId);
        NurseHandoffCreateRequestDTO request = validHandoff(patientId, null);
        UUID actor = UUID.randomUUID();
        assertThatThrownBy(() -> service.createHandoff(actor, hospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.handoff.recipient.required");
        verify(nurseHandoffRepository, never()).save(any());
    }

    @Test
    void createHandoff_refusesSelfAsRecipient_D776() {
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = stubHandoffPatient(hospitalId);
        UUID actor = UUID.randomUUID();
        NurseHandoffCreateRequestDTO request = validHandoff(patientId, actor);
        assertThatThrownBy(() -> service.createHandoff(actor, hospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.handoff.recipient.notSelf");
        verify(nurseHandoffRepository, never()).save(any());
    }

    @Test
    void createHandoff_refusesARecipientNotOnStaffHere_D776() {
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = stubHandoffPatient(hospitalId);
        UUID stranger = UUID.randomUUID();
        when(assignmentRepository.existsActiveWitness(eq(stranger), eq(hospitalId), any())).thenReturn(false);
        NurseHandoffCreateRequestDTO request = validHandoff(patientId, stranger);
        UUID actor = UUID.randomUUID();
        assertThatThrownBy(() -> service.createHandoff(actor, hospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.handoff.recipient.invalid");
        verify(nurseHandoffRepository, never()).save(any());
    }

    /** QA D776, the exact replay: PUT /nurse/handoffs/{id}/complete by the author was 204. */
    @Test
    void completeHandoff_refusesTheAuthor_D776() {
        UUID handoffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        NurseHandoff handoff = NurseHandoff.builder().direction("SHIFT_CHANGE")
            .createdByUserId(author).recipientUserId(UUID.randomUUID()).build();
        when(nurseHandoffRepository.findByIdAndHospital_Id(handoffId, hospitalId)).thenReturn(Optional.of(handoff));

        assertThatThrownBy(() -> service.completeHandoff(handoffId, author, hospitalId))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(handoff.getStatus()).isEqualTo("PENDING");
        verify(nurseHandoffRepository, never()).save(any(NurseHandoff.class));
    }

    @Test
    void completeHandoff_refusesAThirdColleague_D776() {
        UUID handoffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        NurseHandoff handoff = NurseHandoff.builder().direction("SHIFT_CHANGE")
            .createdByUserId(UUID.randomUUID()).recipientUserId(UUID.randomUUID()).build();
        when(nurseHandoffRepository.findByIdAndHospital_Id(handoffId, hospitalId)).thenReturn(Optional.of(handoff));

        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> service.completeHandoff(handoffId, other, hospitalId))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void completeHandoff_theRecipientCompletes_D776() {
        UUID handoffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        NurseHandoff handoff = NurseHandoff.builder().direction("SHIFT_CHANGE")
            .createdByUserId(UUID.randomUUID()).recipientUserId(recipient).build();
        when(nurseHandoffRepository.findByIdAndHospital_Id(handoffId, hospitalId)).thenReturn(Optional.of(handoff));

        service.completeHandoff(handoffId, recipient, hospitalId);

        assertThat(handoff.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void getHandoffRecipients_listsClinicalColleaguesHereButNotTheCaller_D776() {
        UUID hospitalId = UUID.randomUUID();
        UUID me = UUID.randomUUID();
        com.example.hms.model.User self = new com.example.hms.model.User();
        self.setId(me); self.setFirstName("Me"); self.setActive(true);
        com.example.hms.model.User nurse = new com.example.hms.model.User();
        nurse.setId(UUID.randomUUID()); nurse.setFirstName("Awa"); nurse.setLastName("Sanou"); nurse.setActive(true);
        com.example.hms.model.User cashier = new com.example.hms.model.User();
        cashier.setId(UUID.randomUUID()); cashier.setFirstName("Cash"); cashier.setActive(true);
        when(assignmentRepository.findStaffRowsByHospitalId(hospitalId)).thenReturn(List.of(
            assignment(self, "ROLE_NURSE"), assignment(nurse, "ROLE_NURSE"), assignment(cashier, "ROLE_CASHIER")));

        assertThat(service.getHandoffRecipients(me, hospitalId))
            .extracting(com.example.hms.payload.dto.nurse.NurseHandoffRecipientDTO::userId)
            .containsExactly(nurse.getId());
    }

    private static com.example.hms.model.UserRoleHospitalAssignment assignment(com.example.hms.model.User u, String code) {
        com.example.hms.model.Role role = new com.example.hms.model.Role();
        role.setCode(code);
        com.example.hms.model.UserRoleHospitalAssignment a = new com.example.hms.model.UserRoleHospitalAssignment();
        a.setUser(u);
        a.setRole(role);
        a.setActive(true);
        return a;
    }

    @Test
    void createHandoffRejectsUnregisteredPatient() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(mock(Hospital.class)));
        Patient patientEntity = mock(Patient.class);
        when(patientEntity.isRegisteredInHospital(hospitalId)).thenReturn(false);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patientEntity));

        NurseHandoffCreateRequestDTO request = NurseHandoffCreateRequestDTO.builder()
            .patientId(patientId).direction("Shift change").build();

        assertThatThrownBy(() -> service.createHandoff(nurseId, hospitalId, request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void getAnnouncementsReturnsEmptyWhenDatabaseIsEmpty() {
        List<NurseAnnouncementDTO> announcements = service.getAnnouncements(null, 0);

        assertThat(announcements).isEmpty();
    }

    @Test
    void getAnnouncementsReturnsEmptyForHospitalWhenDatabaseIsEmpty() {
        UUID hospitalId = UUID.fromString("12345678-1234-5678-1234-567812345678");

        List<NurseAnnouncementDTO> announcements = service.getAnnouncements(hospitalId, 2);

        assertThat(announcements).isEmpty();
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

        when(nurseHandoffRepository.findByIdAndHospital_Id(handoffId, hospitalId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeHandoff(handoffId, nurseId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void completeHandoffMarksPendingRowCompleted() {
        UUID handoffId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        NurseHandoff handoff = NurseHandoff.builder().direction("Shift change").build();
        when(nurseHandoffRepository.findByIdAndHospital_Id(handoffId, hospitalId))
            .thenReturn(Optional.of(handoff));

        service.completeHandoff(handoffId, nurseId, hospitalId);

        assertThat(handoff.getStatus()).isEqualTo("COMPLETED");
        assertThat(handoff.getCompletedAt()).isNotNull();
        assertThat(handoff.getCompletedByName()).isEqualTo("Nurse"); // no user row stubbed
        verify(nurseHandoffRepository).save(handoff);
    }

    @Test
    void completeHandoffIsIdempotentForCompletedRows() {
        UUID handoffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        NurseHandoff handoff = NurseHandoff.builder().direction("Shift change").status("COMPLETED").build();
        when(nurseHandoffRepository.findByIdAndHospital_Id(handoffId, hospitalId))
            .thenReturn(Optional.of(handoff));

        service.completeHandoff(handoffId, UUID.randomUUID(), hospitalId);

        verify(nurseHandoffRepository, never()).save(any(NurseHandoff.class));
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
            assertThat(task.getType()).isEqualTo("ROUTINE");
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
            assertThat(task.getType()).isEqualTo("FULL_SET");
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

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = mock(Prescription.class);
        when(rx.getId()).thenReturn(UUID.randomUUID());
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(rx.getMedicationName()).thenReturn("Amoxicillin");
        when(rx.getDosage()).thenReturn("500");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn("PO");
        when(rx.getCreatedAt()).thenReturn(fixedNow.minusHours(1));

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));

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

        Prescription rx = mock(Prescription.class);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.DRAFT);

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));

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

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = mock(Prescription.class);
        when(rx.getId()).thenReturn(UUID.randomUUID());
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.TRANSMITTED);
        when(rx.getMedicationName()).thenReturn("Metformin");
        when(rx.getDosage()).thenReturn("1000");
        when(rx.getDoseUnit()).thenReturn(null); // dosage only, no unit
        when(rx.getRoute()).thenReturn(null); // defaults to PO
        when(rx.getCreatedAt()).thenReturn(fixedNow.minusHours(8)); // way past 4h window

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);

            assertThat(tasks).hasSize(1);
            assertThat(tasks.get(0).getStatus()).isEqualTo("OVERDUE");
            assertThat(tasks.get(0).getDose()).isEqualTo("1000"); // dosage only, no unit
            // D109: an unrecorded route stays unrecorded; it is not shown as PO.
            assertThat(tasks.get(0).getRoute()).isNull();
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

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = mock(Prescription.class);
        when(rx.getId()).thenReturn(rxId);
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(rx.getMedicationName()).thenReturn("Aspirin");
        when(rx.getDosage()).thenReturn(null);
        when(rx.getDoseUnit()).thenReturn(null);
        when(rx.getRoute()).thenReturn("IV");
        when(rx.getCreatedAt()).thenReturn(fixedNow.minusHours(1));

        // MAR record shows this prescription was already GIVEN
        // (a one-off order: no frequency, so nothing more is due)
        MedicationAdministrationRecord mar = mock(MedicationAdministrationRecord.class);
        when(mar.getStatus()).thenReturn(MedicationAdministrationStatus.GIVEN);

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rxId))
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

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = mock(Prescription.class);
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

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("John Doe");
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = mock(Prescription.class);
        when(rx.getId()).thenReturn(rxId);
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getMedicationName()).thenReturn("Ceftriaxone");
        when(rx.getDosage()).thenReturn("2");
        when(rx.getDoseUnit()).thenReturn("g");
        when(rx.getRoute()).thenReturn("IV");
        when(rx.getCreatedAt()).thenReturn(LocalDateTime.now().minusHours(1));

        when(prescriptionRepository.findByIdForUpdate(rxId)).thenReturn(Optional.of(rx));

        Staff mockStaff = mock(Staff.class);
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
        // P1 #8 — five-rights not yet verified, so an override reason is required.
        request.setOverrideReason("Pre-eMAR rollout dose; verified manually by charge nurse.");

        NurseMedicationTaskResponseDTO result = service.recordMedicationAdministration(rxId, nurseId, hospitalId, request);

        assertThat(result.getMedication()).isEqualTo("Ceftriaxone");
        assertThat(result.getStatus()).isEqualTo("GIVEN");
        verify(marRepository).save(any(MedicationAdministrationRecord.class));
        verify(staffRepository).findByUserIdAndHospitalId(nurseId, hospitalId);
    }

    @Test
    void recordMedicationAdministrationGivenWithoutOverrideOrVerifyIsRejected() {
        UUID rxId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        Patient mockPatient = mock(Patient.class);
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);
        Prescription rx = mock(Prescription.class);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        when(prescriptionRepository.findByIdForUpdate(rxId)).thenReturn(Optional.of(rx));

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");
        // No overrideReason and no prior verify — must throw.

        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.overrideRequired");
    }

    @Test
    void recordMedicationAdministrationWithHeldStatus() {
        UUID rxId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("Held Patient");
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = mock(Prescription.class);
        when(rx.getId()).thenReturn(rxId);
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getMedicationName()).thenReturn("Warfarin");
        when(rx.getDosage()).thenReturn("5");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn(null); // triggers PO default
        when(rx.getCreatedAt()).thenReturn(null); // triggers now+1h fallback

        when(prescriptionRepository.findByIdForUpdate(rxId)).thenReturn(Optional.of(rx));
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

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("Existing Patient Name");

        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        MedicationAdministrationRecord existingMar = mock(MedicationAdministrationRecord.class);
        when(existingMar.getId()).thenReturn(marId);
        when(existingMar.getPatient()).thenReturn(mockPatient);
        when(existingMar.getHospital()).thenReturn(mockHospital);
        when(existingMar.getMedicationName()).thenReturn("Existing Med");
        when(existingMar.getDose()).thenReturn("100 mg");
        when(existingMar.getRoute()).thenReturn("PO");
        when(existingMar.getScheduledTime()).thenReturn(LocalDateTime.now());
        when(existingMar.getStatus()).thenReturn(MedicationAdministrationStatus.PENDING);

        when(prescriptionRepository.findByIdForUpdate(marId)).thenReturn(Optional.empty());
        when(marRepository.findById(marId)).thenReturn(Optional.of(existingMar));
        when(marRepository.save(existingMar)).thenReturn(existingMar);

        Staff mockStaff = mock(Staff.class);
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

        Hospital otherHospital = mock(Hospital.class);
        when(otherHospital.getId()).thenReturn(otherHospitalId);

        Prescription rx = mock(Prescription.class);
        lenient().when(rx.getHospital()).thenReturn(otherHospital);
        when(prescriptionRepository.findByIdForUpdate(rxId)).thenReturn(Optional.of(rx));

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");

        // The tenant-scoped lock never finds a foreign row: not found, no leak.
        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, scopedHospitalId, request))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }

    @Test
    void recordMedicationAdministrationRejectsCrossHospitalExistingMar() {
        UUID marId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID scopedHospitalId = UUID.randomUUID();
        UUID otherHospitalId = UUID.randomUUID();

        Hospital otherHospital = mock(Hospital.class);
        when(otherHospital.getId()).thenReturn(otherHospitalId);

        MedicationAdministrationRecord existingMar = mock(MedicationAdministrationRecord.class);
        when(existingMar.getHospital()).thenReturn(otherHospital);

        when(prescriptionRepository.findByIdForUpdate(marId)).thenReturn(Optional.empty());
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

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("Held Patient");
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        MedicationAdministrationRecord existingMar = mock(MedicationAdministrationRecord.class);
        when(existingMar.getId()).thenReturn(marId);
        when(existingMar.getPatient()).thenReturn(mockPatient);
        when(existingMar.getHospital()).thenReturn(mockHospital);
        when(existingMar.getMedicationName()).thenReturn("Warfarin");
        when(existingMar.getDose()).thenReturn("5 mg");
        when(existingMar.getRoute()).thenReturn("PO");
        when(existingMar.getScheduledTime()).thenReturn(LocalDateTime.now());
        when(existingMar.getStatus()).thenReturn(MedicationAdministrationStatus.PENDING);

        when(prescriptionRepository.findByIdForUpdate(marId)).thenReturn(Optional.empty());
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

        when(announcementRepository.findByHospital_IdOrderByDateDesc(eq(hospitalId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(a1, a2)));

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
        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = mock(Prescription.class);
        lenient().when(rx.getId()).thenReturn(UUID.randomUUID());
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(rx.getCreatedAt()).thenReturn(fixedNow.minusHours(1));

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        when(announcementRepository.countByHospital_Id(hospitalId)).thenReturn(3L);

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

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

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription overdueRx = mock(Prescription.class);
        lenient().when(overdueRx.getId()).thenReturn(UUID.randomUUID());
        lenient().when(overdueRx.getPatient()).thenReturn(mockPatient);
        lenient().when(overdueRx.getHospital()).thenReturn(mockHospital);
        when(overdueRx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(overdueRx.getCreatedAt()).thenReturn(fixedNow.minusHours(8)); // overdue

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(overdueRx));
        when(announcementRepository.countByHospital_Id(hospitalId)).thenReturn(0L);

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);

            NurseDashboardSummaryDTO summary = service.getDashboardSummary(nurseId, hospitalId);

            assertThat(summary.getVitalsDue()).isZero();
            assertThat(summary.getMedicationsOverdue()).isEqualTo(1);
            assertThat(summary.getMedicationsDue()).isZero();
            assertThat(summary.getAnnouncements()).isZero();
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

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("NullNurse Pat");
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = mock(Prescription.class);
        when(rx.getId()).thenReturn(rxId);
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getMedicationName()).thenReturn("Aspirin");
        when(rx.getDosage()).thenReturn("81");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn("PO");
        when(rx.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(prescriptionRepository.findByIdForUpdate(rxId)).thenReturn(Optional.of(rx));

        MedicationAdministrationRecord saved = MedicationAdministrationRecord.builder()
            .prescription(rx).patient(mockPatient)
            .hospital(mockHospital).medicationName("Aspirin").dose("81 mg")
            .route("PO").scheduledTime(LocalDateTime.now())
            .status(MedicationAdministrationStatus.GIVEN).build();
        when(marRepository.save(any())).thenReturn(saved);

        // nurseId is null → resolveNurseStaff returns empty, no staff set
        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");
        // P1 #8 — five-rights not verified, override reason required to proceed.
        request.setOverrideReason("Edge-case test: anonymous nurse path.");
        NurseMedicationTaskResponseDTO result = service.recordMedicationAdministration(rxId, null, hospitalId, request);
        assertThat(result.getStatus()).isEqualTo("GIVEN");
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

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = mock(Prescription.class);
        when(rx.getId()).thenReturn(UUID.randomUUID());
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        when(rx.getMedicationName()).thenReturn("NullCreated");
        when(rx.getDosage()).thenReturn("10");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn("PO");
        when(rx.getCreatedAt()).thenReturn(null); // null createdAt

        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));

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

        List<NurseWorkboardPatientDTO> result = service.getWorkboard(UUID.randomUUID(), hospitalId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isVitalsDue()).isFalse(); // recent vitals
        assertThat(result.get(0).getMedsDue()).isEqualTo(1L); // one active pending med
    }

    @Test
    void getWorkboardSkipsAdmissionWhenPatientMissing() {
        UUID hospitalId = UUID.randomUUID();

        Admission admission = new Admission();
        admission.setId(UUID.randomUUID());
        admission.setStatus(AdmissionStatus.ACTIVE);

        when(admissionRepository.findActiveAdmissionsByHospital(hospitalId)).thenReturn(List.of(admission));

        List<NurseWorkboardPatientDTO> result = service.getWorkboard(UUID.randomUUID(), hospitalId);

        assertThat(result).isEmpty();
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

        when(admissionRepository.findFlowBoardAdmissions(
                eq(hospitalId),
                argThat(l -> l.containsAll(List.of(AdmissionStatus.ACTIVE, AdmissionStatus.ON_LEAVE, AdmissionStatus.AWAITING_DISCHARGE)))))
            .thenReturn(List.of(criticalAdm, activeAdm, dischargeAdm));

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

        NurseFlowBoardDTO board = service.getPatientFlow(hospitalId, deptId);

        assertThat(board.getActive()).hasSize(1);
        assertThat(board.getActive().get(0).getPatientName()).isEqualTo("Dept Pat");
    }

    @Test
    void getPatientFlowSkipsAdmissionWhenPatientMissing() {
        UUID hospitalId = UUID.randomUUID();

        Admission admission = new Admission();
        admission.setId(UUID.randomUUID());
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setAdmissionDateTime(LocalDateTime.now().minusHours(1));

        when(admissionRepository.findFlowBoardAdmissions(
                eq(hospitalId),
                argThat(l -> l.containsAll(List.of(AdmissionStatus.ACTIVE, AdmissionStatus.ON_LEAVE, AdmissionStatus.AWAITING_DISCHARGE)))))
            .thenReturn(List.of(admission));

        NurseFlowBoardDTO board = service.getPatientFlow(hospitalId, null);

        assertThat(board.getPending()).isEmpty();
        assertThat(board.getActive()).isEmpty();
        assertThat(board.getCritical()).isEmpty();
        assertThat(board.getAwaitingDischarge()).isEmpty();
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
        registerPatientAtHospital(pat, hosp);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp));
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
    void captureVitalsStampsTheNursesAssignmentAtThatHospital() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();

        Patient pat = Patient.builder().build();
        pat.setId(patientId);
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);
        registerPatientAtHospital(pat, hosp);
        com.example.hms.model.UserRoleHospitalAssignment assignment = new com.example.hms.model.UserRoleHospitalAssignment();
        assignment.setHospital(hosp);
        com.example.hms.model.Staff nurse = new com.example.hms.model.Staff();
        nurse.setAssignment(assignment);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp));
        when(staffRepository.findByUserIdAndHospitalId(nurseId, hospitalId)).thenReturn(Optional.of(nurse));

        service.captureVitals(patientId, nurseId, hospitalId,
            NurseVitalCaptureRequestDTO.builder().heartRateBpm(72).build());

        ArgumentCaptor<PatientVitalSign> captor = ArgumentCaptor.forClass(PatientVitalSign.class);
        verify(vitalSignRepository).save(captor.capture());
        assertThat(captor.getValue().getRecordedByStaff()).isSameAs(nurse);
        assertThat(captor.getValue().getRecordedByAssignment()).isSameAs(assignment);
    }

    @Test
    void captureVitalsFlagsClinicallySignificantWhenHeartRateHigh() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        Patient pat6 = Patient.builder().build();
        pat6.setId(patientId);
        Hospital hosp6 = Hospital.builder().build();
        hosp6.setId(hospitalId);
        registerPatientAtHospital(pat6, hosp6);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat6));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp6));

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
        registerPatientAtHospital(pat7, hosp7);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat7));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp7));

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

    @Test
    void getPendingAdmissionsSkipsAdmissionWhenPatientMissing() {
        UUID hospitalId = UUID.randomUUID();

        Admission admission = new Admission();
        admission.setId(UUID.randomUUID());
        admission.setStatus(AdmissionStatus.PENDING);
        admission.setAdmissionDateTime(LocalDateTime.now().minusMinutes(30));

        when(admissionRepository.findActiveAdmissionsByHospital(hospitalId)).thenReturn(List.of(admission));
        when(admissionRepository.findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(
            hospitalId, AdmissionStatus.AWAITING_DISCHARGE)).thenReturn(List.of());

        List<NurseAdmissionSummaryDTO> result = service.getPendingAdmissions(hospitalId, null);

        assertThat(result).isEmpty();
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
        registerPatientAtHospital(pat, hosp);
        User nurse = User.builder().firstName("Jane").lastName("Nurse").build();
        nurse.setId(nurseId);

        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));
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
    void createNursingTaskRefusesATaskDueInThePast_D294() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);
        Patient pat = Patient.builder().firstName("Task").lastName("Pat").build();
        pat.setId(patientId);
        registerPatientAtHospital(pat, hosp);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));

        NurseTaskCreateRequestDTO req = new NurseTaskCreateRequestDTO();
        req.setPatientId(patientId);
        req.setCategory("assessment");
        req.setDescription("QA");
        req.setDueAt(LocalDateTime.of(2020, 1, 1, 8, 0));

        assertThatThrownBy(() -> service.createNursingTask(nurseId, hospitalId, req))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey())
            .isEqualTo("nurse.task.dueAt.past");
        verify(nursingTaskRepository, never()).save(any());
    }

    @Test
    void createNursingTaskThrowsWhenHospitalNotFound() {
        UUID hospitalId = UUID.randomUUID();
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.empty());

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
        registerPatientAtHospital(pat, hosp);
        User author = User.builder().firstName("Jane").lastName("RN").username("jane.rn").build();
        author.setId(nurseId);

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp));
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
        registerPatientAtHospital(pat, hosp);
        User author = User.builder().username("soap_nurse").build(); // no first name → uses username
        author.setId(nurseId);

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp));
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
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.empty());

        NurseCareNoteRequestDTO req = new NurseCareNoteRequestDTO();
        req.setTemplate("DAR");

        UUID nurseUserId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        assertThatThrownBy(() -> service.createCareNote(patientId, nurseUserId, hospitalId, req))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /* ---------- helper ---------- */

    private void registerPatientAtHospital(Patient patient, Hospital hospital) {
        PatientHospitalRegistration reg = new PatientHospitalRegistration(patient, hospital);
        patient.getHospitalRegistrations().add(reg);
    }

    /* ════════════════════════════════════════════════════════════════════
       Cross-hospital tenant isolation
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void captureVitalsRejectsPatientFromDifferentHospital() {
        UUID patientId = UUID.randomUUID();
        UUID nurseHospitalId = UUID.randomUUID();
        UUID patientHospitalId = UUID.randomUUID();

        Hospital patientHosp = Hospital.builder().build();
        patientHosp.setId(patientHospitalId);
        Hospital nurseHosp = Hospital.builder().build();
        nurseHosp.setId(nurseHospitalId);

        Patient pat = Patient.builder().build();
        pat.setId(patientId);
        registerPatientAtHospital(pat, patientHosp); // registered at a DIFFERENT hospital

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findClinicalById(nurseHospitalId)).thenReturn(Optional.of(nurseHosp));

        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder()
            .heartRateBpm(72).build();

        assertThatThrownBy(() -> service.captureVitals(patientId, UUID.randomUUID(), nurseHospitalId, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not registered at this hospital");
    }

    /* ════════════════════════════════════════════════════════════════════
       captureVitals → encounter status transition (ARRIVED → WAITING_FOR_PHYSICIAN)
       ════════════════════════════════════════════════════════════════════ */

    @Test
    void captureVitalsTransitionsEncounterFromArrivedToWaitingForPhysician() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();

        Patient pat = Patient.builder().build();
        pat.setId(patientId);
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);
        registerPatientAtHospital(pat, hosp);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp));
        when(staffRepository.findByUserIdAndHospitalId(nurseId, hospitalId)).thenReturn(Optional.empty());

        Encounter encounter = Encounter.builder().status(EncounterStatus.ARRIVED).build();
        encounter.setId(encounterId);
        when(encounterRepository.findFirstByPatient_IdAndHospital_IdAndStatusOrderByEncounterDateDesc(
            patientId, hospitalId, EncounterStatus.ARRIVED))
            .thenReturn(Optional.of(encounter));
        when(encounterRepository.findFirstByPatient_IdAndHospital_IdAndStatusOrderByEncounterDateDesc(
            patientId, hospitalId, EncounterStatus.TRIAGE))
            .thenReturn(Optional.empty());

        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder()
            .heartRateBpm(72)
            .spo2Percent(98)
            .build();

        service.captureVitals(patientId, nurseId, hospitalId, req);

        // Verify vitals are saved
        verify(vitalSignRepository).save(any(PatientVitalSign.class));

        // Verify encounter status is transitioned to WAITING_FOR_PHYSICIAN
        ArgumentCaptor<Encounter> encCaptor = ArgumentCaptor.forClass(Encounter.class);
        verify(encounterRepository).save(encCaptor.capture());
        assertThat(encCaptor.getValue().getStatus()).isEqualTo(EncounterStatus.WAITING_FOR_PHYSICIAN);
    }

    @Test
    void captureVitalsTransitionsEncounterFromTriageToWaitingForPhysician() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();

        Patient pat = Patient.builder().build();
        pat.setId(patientId);
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);
        registerPatientAtHospital(pat, hosp);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp));
        when(staffRepository.findByUserIdAndHospitalId(nurseId, hospitalId)).thenReturn(Optional.empty());

        // No ARRIVED encounter
        when(encounterRepository.findFirstByPatient_IdAndHospital_IdAndStatusOrderByEncounterDateDesc(
            patientId, hospitalId, EncounterStatus.ARRIVED))
            .thenReturn(Optional.empty());

        // TRIAGE encounter exists
        Encounter encounter = Encounter.builder().status(EncounterStatus.TRIAGE).build();
        encounter.setId(encounterId);
        when(encounterRepository.findFirstByPatient_IdAndHospital_IdAndStatusOrderByEncounterDateDesc(
            patientId, hospitalId, EncounterStatus.TRIAGE))
            .thenReturn(Optional.of(encounter));

        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder()
            .heartRateBpm(72)
            .build();

        service.captureVitals(patientId, nurseId, hospitalId, req);

        // Verify encounter status is transitioned to WAITING_FOR_PHYSICIAN
        ArgumentCaptor<Encounter> encCaptor = ArgumentCaptor.forClass(Encounter.class);
        verify(encounterRepository).save(encCaptor.capture());
        assertThat(encCaptor.getValue().getStatus()).isEqualTo(EncounterStatus.WAITING_FOR_PHYSICIAN);
    }

    @Test
    void captureVitalsDoesNotTransitionWhenNoArrivedOrTriageEncounterExists() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();

        Patient pat = Patient.builder().build();
        pat.setId(patientId);
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);
        registerPatientAtHospital(pat, hosp);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp));
        when(staffRepository.findByUserIdAndHospitalId(nurseId, hospitalId)).thenReturn(Optional.empty());

        // No ARRIVED encounter
        when(encounterRepository.findFirstByPatient_IdAndHospital_IdAndStatusOrderByEncounterDateDesc(
            patientId, hospitalId, EncounterStatus.ARRIVED))
            .thenReturn(Optional.empty());
        // No TRIAGE encounter
        when(encounterRepository.findFirstByPatient_IdAndHospital_IdAndStatusOrderByEncounterDateDesc(
            patientId, hospitalId, EncounterStatus.TRIAGE))
            .thenReturn(Optional.empty());

        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder()
            .heartRateBpm(80)
            .build();

        service.captureVitals(patientId, nurseId, hospitalId, req);

        // Vitals still saved
        verify(vitalSignRepository).save(any(PatientVitalSign.class));
        // No encounter save attempted
        verify(encounterRepository, never()).save(any(Encounter.class));
    }

    /* ════════════════════════════════════════════════════════════════════
       eMAR five-rights barcode-scan verify + administer (P1 #8)
       ════════════════════════════════════════════════════════════════════ */

    private MedicationAdministrationRecord seededMar(UUID patientId, UUID hospitalId, LocalDateTime scheduled) {
        Patient patient = new Patient();
        patient.setId(patientId);
        Hospital hospital = mock(Hospital.class);
        lenient().when(hospital.getId()).thenReturn(hospitalId);
        Prescription rx = new Prescription();
        rx.setMedicationName("Amoxicillin");
        rx.setMedicationCode("AMOX-500");
        rx.setDosage("500 mg");
        rx.setRoute("PO");
        rx.setHospital(hospital);
        rx.setPatient(patient);
        return MedicationAdministrationRecord.builder()
            .prescription(rx)
            .patient(patient)
            .hospital(hospital)
            .medicationName("Amoxicillin")
            .dose("500 mg")
            .route("PO")
            .scheduledTime(scheduled)
            .status(MedicationAdministrationStatus.PENDING)
            .build();
    }

    @Test
    void verifyMedicationPersistsAllFourScanValuesIncludingRoute() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        MedicationAdministrationRecord mar = seededMar(patientId, hospitalId, LocalDateTime.now());

        when(marRepository.findById(marId)).thenReturn(Optional.of(mar));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue(patientId.toString());
        req.setMedicationScanValue("AMOX-500");
        req.setDoseScanValue("500 mg");
        req.setRouteScanValue("PO");

        MarVerificationResponseDTO resp = service.verifyMedicationAdministration(marId, null, hospitalId, req);

        assertThat(resp.isAllPassed()).isTrue();
        ArgumentCaptor<MedicationAdministrationRecord> captor =
            ArgumentCaptor.forClass(MedicationAdministrationRecord.class);
        verify(marRepository).save(captor.capture());
        MedicationAdministrationRecord saved = captor.getValue();
        assertThat(saved.getPatientScanValue()).isEqualTo(patientId.toString());
        assertThat(saved.getMedicationScanValue()).isEqualTo("AMOX-500");
        assertThat(saved.getDoseScanValue()).isEqualTo("500 mg");
        assertThat(saved.getRouteScanValue()).isEqualTo("PO");
        assertThat(saved.getFiveRightsStatus()).isEqualTo(FiveRightsStatus.VERIFIED);
        assertThat(saved.getScanVerifiedAt()).isNotNull();
    }

    @Test
    void verifyMedicationClearsStaleOverrideReasonAndOverrides() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        MedicationAdministrationRecord mar = seededMar(patientId, hospitalId, LocalDateTime.now());
        // Pre-existing OVERRIDDEN state from an earlier attempt.
        mar.setFiveRightsStatus(FiveRightsStatus.OVERRIDDEN);
        mar.setOverrideReason("earlier override that should be cleared");
        mar.setFiveRightsOverrides("[\"DRUG\"]");

        when(marRepository.findById(marId)).thenReturn(Optional.of(mar));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue(patientId.toString());
        req.setMedicationScanValue("AMOX-500");
        req.setDoseScanValue("500 mg");
        req.setRouteScanValue("PO");

        service.verifyMedicationAdministration(marId, null, hospitalId, req);

        ArgumentCaptor<MedicationAdministrationRecord> captor =
            ArgumentCaptor.forClass(MedicationAdministrationRecord.class);
        verify(marRepository).save(captor.capture());
        MedicationAdministrationRecord saved = captor.getValue();
        assertThat(saved.getOverrideReason()).isNull();
        assertThat(saved.getFiveRightsOverrides()).isNull();
        assertThat(saved.getFiveRightsStatus()).isEqualTo(FiveRightsStatus.VERIFIED);
    }

    @Test
    void verifyMedicationFailsAllRightsWhenScansAreWrong() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        MedicationAdministrationRecord mar = seededMar(patientId, hospitalId, LocalDateTime.now());

        when(marRepository.findById(marId)).thenReturn(Optional.of(mar));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue(UUID.randomUUID().toString());
        req.setMedicationScanValue("WRONG-CODE");
        req.setDoseScanValue("250 mg");
        req.setRouteScanValue("IV");

        MarVerificationResponseDTO resp = service.verifyMedicationAdministration(marId, null, hospitalId, req);

        assertThat(resp.isAllPassed()).isFalse();
        assertThat(resp.getFailedChecks())
            .containsExactlyInAnyOrder("PATIENT", "DRUG", "DOSE", "ROUTE");
    }

    @Test
    void administerGivenAfterVerifyAllPassedRecordsVerified() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        MedicationAdministrationRecord mar = seededMar(patientId, hospitalId, LocalDateTime.now());
        mar.setPatientScanValue(patientId.toString());
        mar.setMedicationScanValue("AMOX-500");
        mar.setDoseScanValue("500 mg");
        mar.setRouteScanValue("PO");
        mar.setFiveRightsStatus(FiveRightsStatus.VERIFIED);
        mar.setScanVerifiedAt(LocalDateTime.now());

        when(prescriptionRepository.findByIdForUpdate(marId)).thenReturn(Optional.empty());
        when(marRepository.findById(marId)).thenReturn(Optional.of(mar));
        when(marRepository.save(mar)).thenReturn(mar);

        NurseMedicationAdministrationRequestDTO req = new NurseMedicationAdministrationRequestDTO();
        req.setStatus("GIVEN");

        service.recordMedicationAdministration(marId, null, hospitalId, req);

        assertThat(mar.getFiveRightsStatus()).isEqualTo(FiveRightsStatus.VERIFIED);
        assertThat(mar.getOverrideReason()).isNull();
    }

    @Test
    void administerGivenWithRouteMismatchRequiresOverride() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        MedicationAdministrationRecord mar = seededMar(patientId, hospitalId, LocalDateTime.now());
        // The nurse scanned IV at the bedside but the prescription is PO —
        // the persisted scanned route must drive the re-check, not the
        // prescription's own route. Without the routeScanValue fix this
        // test would silently pass because mar.getRoute() == "PO".
        mar.setPatientScanValue(patientId.toString());
        mar.setMedicationScanValue("AMOX-500");
        mar.setDoseScanValue("500 mg");
        mar.setRouteScanValue("IV");
        mar.setFiveRightsStatus(FiveRightsStatus.NOT_VERIFIED);

        when(prescriptionRepository.findByIdForUpdate(marId)).thenReturn(Optional.empty());
        when(marRepository.findById(marId)).thenReturn(Optional.of(mar));

        NurseMedicationAdministrationRequestDTO req = new NurseMedicationAdministrationRequestDTO();
        req.setStatus("GIVEN");
        // No overrideReason — must throw because ROUTE fails on re-check.

        assertThatThrownBy(() -> service.recordMedicationAdministration(marId, null, hospitalId, req))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.fiveRightsFailedOverrideRequired")
            .satisfies(e -> assertThat(((BusinessException) e).getArgs()[0].toString()).contains("ROUTE"));
    }

    @Test
    void administerGivenOutsideTimeWindowAfterEarlierVerifyRequiresOverride() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        // Scheduled three hours ago — well outside the ±60-minute window.
        MedicationAdministrationRecord mar = seededMar(patientId, hospitalId, LocalDateTime.now().minusHours(3));
        mar.setPatientScanValue(patientId.toString());
        mar.setMedicationScanValue("AMOX-500");
        mar.setDoseScanValue("500 mg");
        mar.setRouteScanValue("PO");
        // Earlier verify ran near the scheduled time and stamped VERIFIED.
        // The administer call should re-evaluate TIME against the *current*
        // administration time and demand an override.
        mar.setFiveRightsStatus(FiveRightsStatus.VERIFIED);

        when(prescriptionRepository.findByIdForUpdate(marId)).thenReturn(Optional.empty());
        when(marRepository.findById(marId)).thenReturn(Optional.of(mar));

        NurseMedicationAdministrationRequestDTO req = new NurseMedicationAdministrationRequestDTO();
        req.setStatus("GIVEN");

        assertThatThrownBy(() -> service.recordMedicationAdministration(marId, null, hospitalId, req))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.fiveRightsFailedOverrideRequired")
            .satisfies(e -> assertThat(((BusinessException) e).getArgs()[0].toString()).contains("TIME"));
    }

    @Test
    void administerGivenOnPreEmarPathStampsScanVerifiedAtForAudit() {
        UUID rxId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(patientId);
        when(patient.getFullName()).thenReturn("Pre-eMAR Pat");
        Hospital hospital = mock(Hospital.class);
        lenient().when(hospital.getId()).thenReturn(hospitalId);
        Prescription rx = mock(Prescription.class);
        when(rx.getId()).thenReturn(rxId);
        lenient().when(rx.getPatient()).thenReturn(patient);
        lenient().when(rx.getHospital()).thenReturn(hospital);
        when(rx.getMedicationName()).thenReturn("Aspirin");
        when(rx.getDosage()).thenReturn("81");
        when(rx.getDoseUnit()).thenReturn("mg");
        when(rx.getRoute()).thenReturn("PO");
        when(rx.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(prescriptionRepository.findByIdForUpdate(rxId)).thenReturn(Optional.of(rx));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        NurseMedicationAdministrationRequestDTO req = new NurseMedicationAdministrationRequestDTO();
        req.setStatus("GIVEN");
        req.setOverrideReason("Pre-eMAR rollout — verified on paper.");

        service.recordMedicationAdministration(rxId, null, hospitalId, req);

        ArgumentCaptor<MedicationAdministrationRecord> captor =
            ArgumentCaptor.forClass(MedicationAdministrationRecord.class);
        verify(marRepository).save(captor.capture());
        MedicationAdministrationRecord saved = captor.getValue();
        assertThat(saved.getFiveRightsStatus()).isEqualTo(FiveRightsStatus.OVERRIDDEN);
        // Audit ranges over scan_verified_at — the override path must stamp
        // it even when there were no barcode scans, otherwise these doses
        // disappear from the safety dashboard.
        assertThat(saved.getScanVerifiedAt()).isNotNull();
        assertThat(saved.getOverrideReason()).isEqualTo("Pre-eMAR rollout — verified on paper.");
    }

    @Test
    void verifyMedicationThrowsWhenMarDoesNotExistAndPrescriptionLookupFails() {
        UUID marId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        when(marRepository.findById(marId)).thenReturn(Optional.empty());
        when(prescriptionRepository.findByIdForUpdate(marId)).thenReturn(Optional.empty());

        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue("p");
        req.setMedicationScanValue("m");
        req.setDoseScanValue("d");
        req.setRouteScanValue("r");

        assertThatThrownBy(() -> service.verifyMedicationAdministration(marId, null, hospitalId, req))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void verifyMedicationThrowsWhenMarIdIsNull() {
        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        assertThatThrownBy(() -> service.verifyMedicationAdministration(null, null, UUID.randomUUID(), req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("MAR identifier");
    }

    @Test
    void verifyMedicationThrowsWhenRequestIsNull() {
        assertThatThrownBy(() -> service.verifyMedicationAdministration(UUID.randomUUID(), null, UUID.randomUUID(), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Verification request body");
    }

    @Test
    void verifyMedicationMaterializesNewMarFromPrescriptionWhenIdResolvesToPrescription() {
        UUID rxId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = new Patient();
        patient.setId(patientId);
        Hospital hospital = mock(Hospital.class);
        lenient().when(hospital.getId()).thenReturn(hospitalId);
        Prescription rx = mock(Prescription.class);
        lenient().when(rx.getId()).thenReturn(rxId);
        lenient().when(rx.getPatient()).thenReturn(patient);
        lenient().when(rx.getHospital()).thenReturn(hospital);
        when(rx.getMedicationName()).thenReturn("Amoxicillin");
        when(rx.getMedicationCode()).thenReturn("AMOX-500");
        when(rx.getDosage()).thenReturn("500 mg");
        when(rx.getRoute()).thenReturn("PO");
        // Created just now: the first dose is due at the order start (D154),
        // keeping the materialised MAR inside the ±60-minute TIME window.
        when(rx.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(marRepository.findById(rxId)).thenReturn(Optional.empty());
        when(prescriptionRepository.findByIdForUpdate(rxId)).thenReturn(Optional.of(rx));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> {
            MedicationAdministrationRecord arg = inv.getArgument(0);
            return arg;
        });

        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue(patientId.toString());
        req.setMedicationScanValue("AMOX-500");
        req.setDoseScanValue("500 mg");
        req.setRouteScanValue("PO");

        MarVerificationResponseDTO resp = service.verifyMedicationAdministration(rxId, null, hospitalId, req);

        assertThat(resp.isAllPassed()).isTrue();
        // marRepository.save is called twice: once to materialize the new
        // MAR from the prescription, then again to stamp the verification.
        verify(marRepository, atLeastOnce()).save(any(MedicationAdministrationRecord.class));
    }

    // ── Pharmacist verification gate (Tier 2 item 33) ───────────────────
    //
    // Scoped deliberately: high-risk medications are blocked until a
    // pharmacist verifies; routine ones keep flowing exactly as before.

    private Prescription highRiskRx(UUID hospitalId, boolean verified) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Awa");
        patient.setLastName("Kabore");
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setHospital(hospital);
        rx.setPatient(patient);
        rx.setMedicationName("Morphine");
        rx.setControlledSubstance(true);
        if (verified) {
            rx.setPharmacistVerifiedAt(LocalDateTime.now().minusHours(1));
        }
        return rx;
    }

    /** Lets the MAR write through, so a test failure is about the gate. */
    private void marSaveEchoes() {
        when(marRepository.save(any(MedicationAdministrationRecord.class)))
            .thenAnswer(i -> {
                MedicationAdministrationRecord r = i.getArgument(0);
                if (r.getId() == null) {
                    r.setId(UUID.randomUUID());
                }
                return r;
            });
    }

    private NurseMedicationAdministrationRequestDTO statusRequest(String status) {
        NurseMedicationAdministrationRequestDTO request =
            new NurseMedicationAdministrationRequestDTO();
        request.setStatus(status);
        return request;
    }

    /**
     * GIVEN also has to clear the PRE-EXISTING five-rights gate (P1 #8),
     * which refuses an unscanned dose without a reason. Supplying one keeps
     * these tests about the pharmacist gate rather than about that one.
     */
    private NurseMedicationAdministrationRequestDTO givenRequest() {
        NurseMedicationAdministrationRequestDTO request = statusRequest("GIVEN");
        request.setOverrideReason("Scanner unavailable on this ward");
        return request;
    }

    @Test
    void anUnverifiedControlledSubstanceCannotBeGiven() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = highRiskRx(hospitalId, false);
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        UUID rxId = rx.getId();
        UUID nurseId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        assertThatThrownBy(() ->
            service.recordMedicationAdministration(rxId, nurseId, hospitalId, given))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.pharmacistVerificationRequired")
            .satisfies(e -> assertThat(((BusinessException) e).getArgs()).containsExactly("Morphine"));

        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }

    @Test
    void aVerifiedControlledSubstanceCanBeGiven() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = highRiskRx(hospitalId, true);
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        marSaveEchoes();

        assertThatCode(() -> service.recordMedicationAdministration(
            rx.getId(), UUID.randomUUID(), hospitalId, givenRequest()))
            .doesNotThrowAnyException();
    }

    @Test
    void anUnverifiedHighRiskDoseCanStillBeRecordedAsHELD() {
        // The gate must never destroy the clinical record. Holding the dose
        // is exactly what a nurse SHOULD do when it cannot be verified, so
        // blocking that would be perverse as well as lossy.
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = highRiskRx(hospitalId, false);
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        marSaveEchoes();

        assertThatCode(() -> service.recordMedicationAdministration(
            rx.getId(), UUID.randomUUID(), hospitalId, statusRequest("HELD")))
            .doesNotThrowAnyException();
    }

    @Test
    void anUnverifiedHighRiskDoseCanStillBeRecordedAsREFUSED() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = highRiskRx(hospitalId, false);
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        marSaveEchoes();

        assertThatCode(() -> service.recordMedicationAdministration(
            rx.getId(), UUID.randomUUID(), hospitalId, statusRequest("REFUSED")))
            .doesNotThrowAnyException();
    }

    @Test
    void anOrdinaryMedicationIsUnaffectedByTheGate() {
        // The scoped decision in one test: routine meds keep flowing with no
        // pharmacist in the loop.
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = highRiskRx(hospitalId, false);
        rx.setControlledSubstance(false);
        rx.setMedicationName("Paracetamol");
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        marSaveEchoes();

        assertThatCode(() -> service.recordMedicationAdministration(
            rx.getId(), UUID.randomUUID(), hospitalId, givenRequest()))
            .doesNotThrowAnyException();
    }

    @Test
    void getOrderTasksSkipsAnOrderWhosePatientNoLongerExists() {
        // dev, 2026-09-13: a hard-deleted patient left an imaging order behind; the lazy
        // proxy threw EntityNotFoundException on getFullName() and the whole board was a 500.
        // One orphan of each kind, one live lab and one live imaging order.
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID livePatientId = UUID.randomUUID();
        UUID gonePatientId = UUID.randomUUID();

        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(livePatientId, "Ann Assigned", "Ann", "Assigned"),
                patient(gonePatientId, "Gone Patient", "Gone", "Patient")));

        Patient live = mock(Patient.class);
        when(live.getId()).thenReturn(livePatientId);
        when(live.getFullName()).thenReturn("Ann Assigned");
        Patient gone = mock(Patient.class);
        when(gone.getId()).thenReturn(gonePatientId);
        when(gone.getFullName()).thenThrow(new jakarta.persistence.EntityNotFoundException(
            "Unable to find com.example.hms.model.Patient with id " + gonePatientId));

        LabOrder liveDraw = mock(LabOrder.class);
        when(liveDraw.getId()).thenReturn(UUID.randomUUID());
        when(liveDraw.getPatient()).thenReturn(live);
        when(liveDraw.getPriority()).thenReturn("ROUTINE");
        when(liveDraw.getOrderDatetime()).thenReturn(LocalDateTime.of(2026, 9, 13, 8, 0));
        LabOrder orphanedDraw = mock(LabOrder.class);
        when(orphanedDraw.getId()).thenReturn(UUID.randomUUID());
        when(orphanedDraw.getPatient()).thenReturn(gone);
        when(labOrderRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any()))
            .thenReturn(List.of(liveDraw, orphanedDraw));

        ImagingOrder liveScan = mock(ImagingOrder.class);
        when(liveScan.getId()).thenReturn(UUID.randomUUID());
        when(liveScan.getPatient()).thenReturn(live);
        when(liveScan.getPriority()).thenReturn(null);
        when(liveScan.getOrderedAt()).thenReturn(LocalDateTime.of(2026, 9, 13, 9, 0));
        ImagingOrder orphanedImaging = mock(ImagingOrder.class);
        when(orphanedImaging.getId()).thenReturn(UUID.randomUUID());
        when(orphanedImaging.getPatient()).thenReturn(gone);
        when(imagingOrderRepository.findByHospital_IdAndStatusInOrderByOrderedAtDesc(eq(hospitalId), any()))
            .thenReturn(List.of(orphanedImaging, liveScan));

        ProcedureOrder orphanedProcedure = mock(ProcedureOrder.class);
        when(orphanedProcedure.getId()).thenReturn(UUID.randomUUID());
        when(orphanedProcedure.getPatient()).thenReturn(gone);
        when(procedureOrderRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any()))
            .thenReturn(List.of(orphanedProcedure));

        List<NurseOrderTaskResponseDTO> tasks = service.getOrderTasks(nurseId, hospitalId, null, 20);

        assertThat(tasks)
            .extracting(NurseOrderTaskResponseDTO::getOrderType, NurseOrderTaskResponseDTO::getPatientName)
            .containsExactly(tuple("LAB", "Ann Assigned"), tuple("IMAGING", "Ann Assigned"));
    }

    @Test
    void recordMedicationAdministrationOverrideSurvivesSerializationFailure() {
        // A mapper that fails: the five-rights override falls back to toString, the dose is still recorded.
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(tools.jackson.databind.exc.MismatchedInputException.from((tools.jackson.core.JsonParser) null, Object.class, "boom"));
        NurseTaskServiceImpl failing = new NurseTaskServiceImpl(
            nurseDashboardService, prescriptionRepository, marRepository,
            vitalSignRepository, announcementRepository, staffRepository, hospitalRepository,
            admissionRepository, encounterRepository, patientRepository, nursingTaskRepository,
            nursingNoteRepository, pharmacistVerificationService, notificationRepository, userRepository,
            nurseHandoffRepository, labOrderRepository, imagingOrderRepository, procedureOrderRepository,
            fiveRightsService, failingMapper, news2EscalationService,
            new com.example.hms.service.support.DischargedPatientGuard(admissionRepository, encounterRepository),
            assignmentRepository);

        UUID rxId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        when(mockPatient.getFullName()).thenReturn("John Doe");
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);

        Prescription rx = mock(Prescription.class);
        when(rx.getId()).thenReturn(rxId);
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        when(rx.getMedicationName()).thenReturn("Ceftriaxone");
        when(rx.getDosage()).thenReturn("2");
        when(rx.getDoseUnit()).thenReturn("g");
        when(rx.getRoute()).thenReturn("IV");
        when(rx.getCreatedAt()).thenReturn(LocalDateTime.now().minusHours(1));
        when(prescriptionRepository.findByIdForUpdate(rxId)).thenReturn(Optional.of(rx));
        when(staffRepository.findByUserIdAndHospitalId(nurseId, hospitalId)).thenReturn(Optional.of(mock(Staff.class)));

        ArgumentCaptor<MedicationAdministrationRecord> captor = ArgumentCaptor.forClass(MedicationAdministrationRecord.class);
        when(marRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");
        request.setOverrideReason("Pre-eMAR rollout dose; verified manually by charge nurse.");

        NurseMedicationTaskResponseDTO result = failing.recordMedicationAdministration(rxId, nurseId, hospitalId, request);

        assertThat(result.getStatus()).isEqualTo("GIVEN");
        assertThat(captor.getValue().getFiveRightsOverrides()).startsWith("[");
    }

    /* ════════════════════════════════════════════════════════════════════
       QA round 2: D76 (PENDING filter), D77 (dose schedule), D78 (verified row)
       ════════════════════════════════════════════════════════════════════ */

    private Prescription scheduledRx(UUID hospitalId, String frequency) {
        Prescription rx = highRiskRx(hospitalId, false);
        rx.setControlledSubstance(false);
        rx.setMedicationName("Ceftriaxone");
        rx.setRoute("IV");
        rx.setFrequency(frequency);
        rx.setMedicationCode("CEF-1G");
        rx.setDosage("1 g");
        rx.setCreatedAt(LocalDateTime.now().minusHours(4));
        return rx;
    }

    private MedicationAdministrationRecord closedDose(Prescription rx, LocalDateTime slot,
                                                      MedicationAdministrationStatus status) {
        MedicationAdministrationRecord r = MedicationAdministrationRecord.builder()
            .prescription(rx).patient(rx.getPatient()).hospital(rx.getHospital())
            .medicationName(rx.getMedicationName()).scheduledTime(slot).status(status).build();
        r.setId(UUID.randomUUID());
        return r;
    }

    @Test
    void D154_theFirstDoseIsDueWhenTheOrderIsSigned() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        rx.setStatus(PrescriptionStatus.SIGNED);
        LocalDateTime signed = LocalDateTime.now().minusMinutes(10);
        rx.setSignedAt(signed);
        UUID patientId = rx.getPatient().getId();
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Awa Kabore", "Awa", "Kabore")));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));

        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);

        // Not four hours after the order: at its start.
        assertThat(tasks.get(0).getDueTime()).isEqualTo(signed);
    }

    @Test
    void D154_aQ12hDoseCannotBeGivenSeventyTwoSecondsAfterThePreviousOne() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        rx.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        // The first dose was just given; the next slot is twelve hours away.
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(closedDose(rx, rx.getCreatedAt(), MedicationAdministrationStatus.GIVEN)));
        UUID rxId = rx.getId();
        UUID nurseId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, given))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.doseNotYetDue");
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }

    @Test
    void D136_aDischargedPatientsInpatientOrderIsNoLongerDue() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        rx.setStatus(PrescriptionStatus.SIGNED);
        UUID patientId = rx.getPatient().getId();
        // Ordered during a stay that has since been discharged, on no
        // particular encounter (the QA Ceftriaxone).
        Admission stay = new Admission();
        stay.setAdmissionDateTime(rx.getCreatedAt().minusHours(1));
        stay.setActualDischargeDateTime(LocalDateTime.now().minusMinutes(1));
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Awa Kabore", "Awa", "Kabore")));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        when(admissionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(stay));

        assertThat(service.getMedicationTasks(nurseId, hospitalId, "PENDING")).isEmpty();
    }

    @Test
    void D136_anOrderOnAClosedStayEncounterHasEndedButAnOutpatientOrderHasNot() {
        Prescription onStay = new Prescription();
        com.example.hms.model.Encounter closedStay = new com.example.hms.model.Encounter();
        closedStay.setAdmissionId(UUID.randomUUID());
        closedStay.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
        onStay.setEncounter(closedStay);
        Prescription outpatient = new Prescription();
        outpatient.setCreatedAt(LocalDateTime.now());
        com.example.hms.model.Encounter visit = new com.example.hms.model.Encounter();
        visit.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
        outpatient.setEncounter(visit);

        assertThat(NurseTaskServiceImpl.endedWithAStay(onStay, List.of())).isTrue();
        assertThat(NurseTaskServiceImpl.endedWithAStay(outpatient, List.of())).isFalse();
    }

    @Test
    void D76_pendingFilterReturnsTheDosesStillToGive() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        UUID patientId = rx.getPatient().getId();
        rx.setStatus(PrescriptionStatus.SIGNED);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Awa Kabore", "Awa", "Kabore")));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));

        List<NurseMedicationTaskResponseDTO> pending = service.getMedicationTasks(nurseId, hospitalId, "PENDING");

        assertThat(pending).extracting(NurseMedicationTaskResponseDTO::getMedication).containsExactly("Ceftriaxone");
        assertThat(pending.get(0).getStatus()).isIn("DUE", "OVERDUE");
        assertThat(pending.get(0).getRoute()).isEqualTo("IV");
    }

    @Test
    void D77_aRepeatingOrderSchedulesItsNextDoseAfterOneIsGiven() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        UUID patientId = rx.getPatient().getId();
        rx.setStatus(PrescriptionStatus.SIGNED);
        LocalDateTime firstSlot = rx.getCreatedAt();
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Awa Kabore", "Awa", "Kabore")));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(closedDose(rx, firstSlot, MedicationAdministrationStatus.GIVEN)));

        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getStatus()).isEqualTo("DUE");
        assertThat(tasks.get(0).getDueTime()).isEqualTo(firstSlot.plusHours(12));
    }

    @Test
    void D77_theSameDoseCannotBeRecordedTwice() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "once");
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(closedDose(rx, rx.getCreatedAt(), MedicationAdministrationStatus.GIVEN)));
        UUID rxId = rx.getId();
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        UUID nurseId = UUID.randomUUID();
        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, given))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.noDoseDue");
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }

    @Test
    void D77_theSecondRecordFillsTheNextSlotNotTheSameOne() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "BID");
        // Thirteen hours on, the second BID dose is due (D154: not before).
        rx.setCreatedAt(LocalDateTime.now().minusHours(13));
        LocalDateTime firstSlot = rx.getCreatedAt();
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(closedDose(rx, firstSlot, MedicationAdministrationStatus.GIVEN)));
        marSaveEchoes();

        NurseMedicationTaskResponseDTO out =
            service.recordMedicationAdministration(rx.getId(), UUID.randomUUID(), hospitalId, givenRequest());

        assertThat(out.getDueTime()).isEqualTo(firstSlot.plusHours(12));
    }

    @Test
    void D77_anAlreadyRecordedMarRowCannotBeRecordedAgain() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        MedicationAdministrationRecord given = closedDose(rx, LocalDateTime.now(), MedicationAdministrationStatus.GIVEN);
        when(marRepository.findById(given.getId())).thenReturn(Optional.of(given));
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        UUID marId = given.getId();
        NurseMedicationAdministrationRequestDTO again = givenRequest();

        UUID nurseId = UUID.randomUUID();
        assertThatThrownBy(() -> service.recordMedicationAdministration(marId, nurseId, hospitalId, again))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.alreadyRecorded");
    }

    @Test
    void D77_reVerifyingTheSameDoseReusesItsPendingRow() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        MedicationAdministrationRecord pending =
            closedDose(rx, rx.getCreatedAt(), MedicationAdministrationStatus.PENDING);
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId())).thenReturn(List.of(pending));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(i -> i.getArgument(0));
        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue("p");
        req.setMedicationScanValue("m");
        req.setDoseScanValue("d");
        req.setRouteScanValue("IV");

        MarVerificationResponseDTO out = service.verifyMedicationAdministration(rx.getId(), UUID.randomUUID(), hospitalId, req);

        assertThat(out.getMarId()).isEqualTo(pending.getId());
        verify(marRepository).save(pending);
    }

    @Test
    void D78_administeringByPrescriptionIdFinalisesTheVerifiedRowWithoutAnOverride() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        rx.setCreatedAt(LocalDateTime.now()); // the slot is now (D154)
        MedicationAdministrationRecord verified =
            closedDose(rx, rx.getCreatedAt(), MedicationAdministrationStatus.PENDING);
        verified.setFiveRightsStatus(FiveRightsStatus.VERIFIED);
        verified.setPatientScanValue(rx.getPatient().getId().toString());
        verified.setMedicationScanValue("CEF-1G");
        verified.setDoseScanValue("1 g");
        verified.setRouteScanValue("IV");
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId())).thenReturn(List.of(verified));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(i -> i.getArgument(0));

        NurseMedicationTaskResponseDTO out =
            service.recordMedicationAdministration(rx.getId(), UUID.randomUUID(), hospitalId, statusRequest("GIVEN"));

        assertThat(out.getId()).isEqualTo(verified.getId());
        assertThat(verified.getStatus()).isEqualTo(MedicationAdministrationStatus.GIVEN);
        assertThat(verified.getFiveRightsStatus()).isEqualTo(FiveRightsStatus.VERIFIED);
    }

    @Test
    void D77_aForeignTenantPrescriptionIdIsNeverLockedByBareId() {
        UUID hospitalId = UUID.randomUUID();
        Prescription foreign = scheduledRx(UUID.randomUUID(), "q12h");
        lenient().when(prescriptionRepository.findByIdForUpdate(foreign.getId())).thenReturn(Optional.of(foreign));
        UUID rxId = foreign.getId();
        doReturn(Optional.empty())
            .when(prescriptionRepository).findByIdAndHospitalIdForUpdate(rxId, hospitalId);
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        UUID nurseId = UUID.randomUUID();
        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, given))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(prescriptionRepository).findByIdAndHospitalIdForUpdate(rxId, hospitalId);
        verify(prescriptionRepository, never()).findByIdForUpdate(rxId);
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }

    @Test
    void D77_aMarRowFinalisedConcurrentlyIsRefusedAfterTheLock() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        MedicationAdministrationRecord stale =
            closedDose(rx, rx.getCreatedAt(), MedicationAdministrationStatus.PENDING);
        when(marRepository.findById(stale.getId())).thenReturn(Optional.of(stale));
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        // Another nurse committed GIVEN while this call waited on the lock.
        doReturn(Optional.of(MedicationAdministrationStatus.GIVEN))
            .when(marRepository).findStatusById(stale.getId());
        UUID marId = stale.getId();
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        UUID nurseId = UUID.randomUUID();
        assertThatThrownBy(() -> service.recordMedicationAdministration(marId, nurseId, hospitalId, given))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.alreadyRecorded");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(prescriptionRepository, marRepository);
        order.verify(prescriptionRepository).findByIdAndHospitalIdForUpdate(rx.getId(), hospitalId);
        order.verify(marRepository).findStatusById(marId);
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }

    /* ── QA round 7 (D411, D431) ─────────────────────────────────────── */

    private UUID[] registeredPatient() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient pat = Patient.builder().build();
        pat.setId(patientId);
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);
        registerPatientAtHospital(pat, hosp);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp));
        return new UUID[] {patientId, hospitalId};
    }

    private static com.example.hms.model.Admission stay(com.example.hms.enums.AdmissionStatus status) {
        com.example.hms.model.Admission a = new com.example.hms.model.Admission();
        a.setId(UUID.randomUUID());
        a.setStatus(status);
        return a;
    }

    @Test
    void d431_vitalsAreRefusedAfterDischargeWithNoOpenVisit() {
        UUID[] ids = registeredPatient();
        when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(eq(ids[0]), eq(ids[1]), any()))
            .thenReturn(List.of(stay(com.example.hms.enums.AdmissionStatus.DISCHARGED)));
        when(encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(eq(ids[0]), eq(ids[1]), any()))
            .thenReturn(List.of());
        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder().temperatureCelsius(37.0).build();
        UUID nurseId = UUID.randomUUID();

        assertThatThrownBy(() -> service.captureVitals(ids[0], nurseId, ids[1], req))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.vitals.patientDischarged");
        verify(vitalSignRepository, never()).save(any());
    }

    @Test
    void d431_vitalsStillRecordForAnInpatientAndForAReturningOutpatient() {
        UUID[] ids = registeredPatient();
        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder().temperatureCelsius(37.0).build();
        UUID nurseId = UUID.randomUUID();
        // Inpatient: an open stay.
        when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(eq(ids[0]), eq(ids[1]), any()))
            .thenReturn(List.of(stay(com.example.hms.enums.AdmissionStatus.ACTIVE)));
        service.captureVitals(ids[0], nurseId, ids[1], req);
        // Discharged earlier, back today for a visit.
        when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(eq(ids[0]), eq(ids[1]), any()))
            .thenReturn(List.of(stay(com.example.hms.enums.AdmissionStatus.DISCHARGED)));
        when(encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(eq(ids[0]), eq(ids[1]), any()))
            .thenReturn(List.of(new Encounter()));
        service.captureVitals(ids[0], nurseId, ids[1], req);

        verify(vitalSignRepository, times(2)).save(any());
    }

    @Test
    void d431_aCompletedTaskCannotBeCompletedAgain() {
        UUID taskId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        NursingTask task = NursingTask.builder()
            .category("WOUND_CARE").description("x").priority("ROUTINE").status("COMPLETED")
            .completedByName("First Nurse").build();
        task.setId(taskId);
        when(nursingTaskRepository.findByIdAndHospital_Id(taskId, hospitalId)).thenReturn(Optional.of(task));
        UUID nurseId = UUID.randomUUID();

        assertThatThrownBy(() -> service.completeNursingTask(taskId, nurseId, hospitalId, null))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        assertThat(task.getCompletedByName()).isEqualTo("First Nurse");
        verify(nursingTaskRepository, never()).save(any());
    }

    @Test
    void d411_aDoseScannedByOneNurseCannotBeGivenByAnother() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        MedicationAdministrationRecord mar = seededMar(patientId, hospitalId, LocalDateTime.now());
        mar.setPatientScanValue(patientId.toString());
        mar.setMedicationScanValue("AMOX-500");
        mar.setDoseScanValue("500 mg");
        mar.setRouteScanValue("PO");
        mar.setFiveRightsStatus(FiveRightsStatus.VERIFIED);
        mar.setScanVerifiedAt(LocalDateTime.now());
        Staff scanner = new Staff();
        scanner.setId(UUID.randomUUID());
        mar.setAdministeredByStaff(scanner);
        UUID otherNurse = UUID.randomUUID();
        Staff other = new Staff();
        other.setId(UUID.randomUUID());
        when(prescriptionRepository.findByIdForUpdate(marId)).thenReturn(Optional.empty());
        when(marRepository.findById(marId)).thenReturn(Optional.of(mar));
        when(staffRepository.findByUserIdAndHospitalId(otherNurse, hospitalId)).thenReturn(Optional.of(other));
        NurseMedicationAdministrationRequestDTO req = new NurseMedicationAdministrationRequestDTO();
        req.setStatus("GIVEN");

        assertThatThrownBy(() -> service.recordMedicationAdministration(marId, otherNurse, hospitalId, req))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.scanByAnotherNurse");
        assertThat(mar.getAdministeredByStaff()).isSameAs(scanner);
        assertThat(mar.getStatus()).isEqualTo(MedicationAdministrationStatus.PENDING);
    }

    @Test
    void d411_theNurseWhoScannedGivesTheDose() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        MedicationAdministrationRecord mar = seededMar(patientId, hospitalId, LocalDateTime.now());
        mar.setPatientScanValue(patientId.toString());
        mar.setMedicationScanValue("AMOX-500");
        mar.setDoseScanValue("500 mg");
        mar.setRouteScanValue("PO");
        mar.setFiveRightsStatus(FiveRightsStatus.VERIFIED);
        mar.setScanVerifiedAt(LocalDateTime.now());
        UUID nurseId = UUID.randomUUID();
        Staff scanner = new Staff();
        scanner.setId(UUID.randomUUID());
        mar.setAdministeredByStaff(scanner);
        when(prescriptionRepository.findByIdForUpdate(marId)).thenReturn(Optional.empty());
        when(marRepository.findById(marId)).thenReturn(Optional.of(mar));
        when(marRepository.save(mar)).thenReturn(mar);
        when(staffRepository.findByUserIdAndHospitalId(nurseId, hospitalId)).thenReturn(Optional.of(scanner));
        NurseMedicationAdministrationRequestDTO req = new NurseMedicationAdministrationRequestDTO();
        req.setStatus("GIVEN");

        service.recordMedicationAdministration(marId, nurseId, hospitalId, req);

        assertThat(mar.getStatus()).isEqualTo(MedicationAdministrationStatus.GIVEN);
        assertThat(mar.getFiveRightsStatus()).isEqualTo(FiveRightsStatus.VERIFIED);
    }

    /* ── QA round 10 (D702) ─────────────────────────────────────────── */

    @Test
    void d702_theEmarOfAnAdmittedPatientListsOnlyTheOrdersOfTheCurrentStay() {
        // QA D702: PATIENT_A2's consultation orders (paracetamol SIGNED, an
        // antimalarial already dispensed for home) were listed as due doses
        // once he was admitted, next to the stay's own paracetamol.
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Admission stay = new Admission();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionDateTime(LocalDateTime.now().minusHours(3));
        Encounter consultation = new Encounter();
        consultation.setEncounterType(com.example.hms.enums.EncounterType.CONSULTATION);
        consultation.setStatus(com.example.hms.enums.EncounterStatus.IN_PROGRESS);
        Encounter stayEncounter = new Encounter();
        stayEncounter.setEncounterType(com.example.hms.enums.EncounterType.INPATIENT);
        stayEncounter.setStatus(com.example.hms.enums.EncounterStatus.IN_PROGRESS);
        stayEncounter.setAdmissionId(stay.getId());

        Prescription takeHome = scheduledRx(hospitalId, "q6h");
        takeHome.setStatus(PrescriptionStatus.SIGNED);
        takeHome.setMedicationName("Paracetamol 500 mg");
        takeHome.setEncounter(consultation);
        UUID patientId = takeHome.getPatient().getId();
        Prescription dispensedForHome = scheduledRx(hospitalId, "q12h");
        dispensedForHome.setPatient(takeHome.getPatient());
        dispensedForHome.setStatus(PrescriptionStatus.TRANSMITTED);
        dispensedForHome.setMedicationName("Artemether-Lumefantrine");
        dispensedForHome.setEncounter(consultation);
        Prescription stayOrder = scheduledRx(hospitalId, "q6h");
        stayOrder.setPatient(takeHome.getPatient());
        stayOrder.setStatus(PrescriptionStatus.SIGNED);
        stayOrder.setMedicationName("Paracetamol 1 g");
        stayOrder.setEncounter(stayEncounter);
        Prescription unanchoredBeforeStay = scheduledRx(hospitalId, "q8h");
        unanchoredBeforeStay.setPatient(takeHome.getPatient());
        unanchoredBeforeStay.setStatus(PrescriptionStatus.SIGNED);
        unanchoredBeforeStay.setMedicationName("Old order");
        unanchoredBeforeStay.setCreatedAt(LocalDateTime.now().minusDays(2));

        doReturn(java.util.Map.of(patientId, stay)).when(service).activeStaysByPatient(hospitalId);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Awa Kabore", "Awa", "Kabore")));
        lenient().when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(takeHome, dispensedForHome, stayOrder, unanchoredBeforeStay));

        assertThat(service.getMedicationTasks(nurseId, hospitalId, null))
            .extracting(NurseMedicationTaskResponseDTO::getMedication)
            .containsExactly("Paracetamol 1 g");
    }

    @Test
    void d702_anOrderOfAnEarlierStayIsNotTheCurrentStays() {
        Admission current = new Admission();
        current.setId(UUID.randomUUID());
        Encounter earlier = new Encounter();
        earlier.setEncounterType(com.example.hms.enums.EncounterType.INPATIENT);
        earlier.setAdmissionId(UUID.randomUUID());
        Prescription rx = new Prescription();
        rx.setEncounter(earlier);
        rx.setCreatedAt(LocalDateTime.now());
        assertThat(NurseTaskServiceImpl.orderOfCurrentStay(rx, current)).isFalse();
        assertThat(NurseTaskServiceImpl.orderOfCurrentStay(rx, null)).isFalse();
        Prescription unanchored = new Prescription();
        unanchored.setCreatedAt(LocalDateTime.now());
        current.setAdmissionDateTime(LocalDateTime.now().minusHours(1));
        assertThat(NurseTaskServiceImpl.orderOfCurrentStay(unanchored, current)).isTrue();
    }

    @Test
    void d702_activeStaysByPatientMapsEachAdmittedPatientToTheirStay() {
        UUID hospitalId = UUID.randomUUID();
        doCallRealMethod().when(service).activeStaysByPatient(any());
        Admission stay = new Admission();
        stay.setId(UUID.randomUUID());
        com.example.hms.model.Patient p = new com.example.hms.model.Patient();
        p.setId(UUID.randomUUID());
        stay.setPatient(p);
        when(admissionRepository.findActiveAdmissionsByHospital(hospitalId)).thenReturn(List.of(stay));
        assertThat(service.activeStaysByPatient(hospitalId)).containsEntry(p.getId(), stay);
        assertThat(service.activeStaysByPatient(null)).isEmpty();
    }

    /* ── QA round 8 (D514, D525) ─────────────────────────────────────── */

    @Test
    void d514_theEmarDoesNotListAnOutpatientsTakeHomePrescription() {
        // QA D514: GET /nurse/medications/mar listed the prescriptions of a
        // patient nobody admitted (written at a consultation) as due doses.
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        rx.setStatus(PrescriptionStatus.SIGNED);
        Encounter consultation = new Encounter();
        consultation.setEncounterType(com.example.hms.enums.EncounterType.CONSULTATION);
        consultation.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
        rx.setEncounter(consultation);
        UUID patientId = rx.getPatient().getId();
        doReturn(java.util.Map.of()).when(service).activeStaysByPatient(hospitalId);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Awa Kabore", "Awa", "Kabore")));
        lenient().when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));

        assertThat(service.getMedicationTasks(nurseId, hospitalId, null)).isEmpty();
    }

    @Test
    void d514_theDueCountsExcludeAnOutpatientsTakeHomePrescription() {
        // QA D514: the dashboard's due/overdue counts follow the eMAR list's
        // rule, so a consultation's take-home order is not counted either.
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        rx.setStatus(PrescriptionStatus.SIGNED);
        Encounter consultation = new Encounter();
        consultation.setEncounterType(com.example.hms.enums.EncounterType.CONSULTATION);
        consultation.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
        rx.setEncounter(consultation);
        UUID patientId = rx.getPatient().getId();
        doReturn(java.util.Map.of()).when(service).activeStaysByPatient(hospitalId);
        lenient().when(nurseDashboardService.getPatientsForNurse(eq(nurseId), eq(hospitalId), any()))
            .thenReturn(List.of(patient(patientId, "Awa Kabore", "Awa", "Kabore")));
        lenient().when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));

        NurseDashboardSummaryDTO summary = service.getDashboardSummary(nurseId, hospitalId);

        assertThat(summary.getMedicationsDue()).isZero();
        assertThat(summary.getMedicationsOverdue()).isZero();
    }

    @Test
    void d702_aTakeHomeCourseOfAnEmergencyVisitClosedBeforeTheAdmissionIsNotAWardDose() {
        // #940 review: an ED visit checked out at 09:00 with a course for home,
        // then a same-day admission at 18:00 — the course must not be DUE.
        LocalDateTime admittedAt = LocalDateTime.now().minusHours(1);
        Admission stay = new Admission();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionDateTime(admittedAt);
        Encounter closedEd = new Encounter();
        closedEd.setEncounterType(com.example.hms.enums.EncounterType.EMERGENCY);
        closedEd.setCheckoutTimestamp(admittedAt.minusHours(9));
        Prescription takeHome = new Prescription();
        takeHome.setEncounter(closedEd);
        takeHome.setCreatedAt(admittedAt.minusHours(10));
        assertThat(NurseTaskServiceImpl.orderOfCurrentStay(takeHome, stay)).isFalse();

        Encounter openEd = new Encounter();
        openEd.setEncounterType(com.example.hms.enums.EncounterType.EMERGENCY);
        openEd.setCheckoutTimestamp(admittedAt);
        Prescription edDose = new Prescription();
        edDose.setEncounter(openEd);
        edDose.setCreatedAt(admittedAt.minusHours(2));
        assertThat(NurseTaskServiceImpl.orderOfCurrentStay(edDose, stay)).isTrue();
        openEd.setCheckoutTimestamp(null);
        assertThat(NurseTaskServiceImpl.orderOfCurrentStay(edDose, stay)).isTrue();
    }

    @Test
    void d514_anAdmittedPatientsDoseAndAnOpenEmergencyVisitsDoseAreStillListed() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription inBed = scheduledRx(hospitalId, "q12h");
        inBed.setStatus(PrescriptionStatus.SIGNED);
        Prescription inEd = scheduledRx(hospitalId, "q12h");
        inEd.setStatus(PrescriptionStatus.SIGNED);
        inEd.setMedicationName("Paracetamol IV");
        Encounter ed = new Encounter();
        ed.setEncounterType(com.example.hms.enums.EncounterType.EMERGENCY);
        ed.setStatus(com.example.hms.enums.EncounterStatus.IN_PROGRESS);
        inEd.setEncounter(ed);
        UUID admittedId = inBed.getPatient().getId();
        UUID edId = inEd.getPatient().getId();
        doReturn(java.util.Map.of(admittedId, new Admission())).when(service).activeStaysByPatient(hospitalId);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(admittedId, "Awa Kabore", "Awa", "Kabore"),
                patient(edId, "Issa Sawadogo", "Issa", "Sawadogo")));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(admittedId, hospitalId)).thenReturn(List.of(inBed));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(edId, hospitalId)).thenReturn(List.of(inEd));

        assertThat(service.getMedicationTasks(nurseId, hospitalId, null))
            .extracting(NurseMedicationTaskResponseDTO::getMedication)
            .containsExactlyInAnyOrder("Ceftriaxone", "Paracetamol IV");
    }

    @Test
    void d525_vitalsAreRefusedWhenTheOnlyOpenVisitPredatesTheDischarge() {
        // QA D525 (D431 did not hold): POST /nurse/patients/{id}/vitals after
        // discharge was 200 because a visit left open from before the stay
        // counted as an open visit.
        UUID[] ids = registeredPatient();
        com.example.hms.model.Admission closed = stay(com.example.hms.enums.AdmissionStatus.DISCHARGED);
        closed.setActualDischargeDateTime(LocalDateTime.now().minusHours(1));
        when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(eq(ids[0]), eq(ids[1]), any()))
            .thenReturn(List.of(closed));
        Encounter stale = new Encounter();
        stale.setEncounterDate(LocalDateTime.now().minusDays(3));
        when(encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(eq(ids[0]), eq(ids[1]), any()))
            .thenReturn(List.of(stale));
        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder().temperatureCelsius(37.0).build();
        UUID nurseId = UUID.randomUUID();

        assertThatThrownBy(() -> service.captureVitals(ids[0], nurseId, ids[1], req))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.vitals.patientDischarged");
        verify(vitalSignRepository, never()).save(any());
    }

    @Test
    void d525_aVisitOpenedAfterTheDischargeStillAllowsVitals() {
        UUID[] ids = registeredPatient();
        com.example.hms.model.Admission closed = stay(com.example.hms.enums.AdmissionStatus.DISCHARGED);
        closed.setActualDischargeDateTime(LocalDateTime.now().minusDays(2));
        when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(eq(ids[0]), eq(ids[1]), any()))
            .thenReturn(List.of(closed));
        Encounter today = new Encounter();
        today.setEncounterDate(LocalDateTime.now().minusMinutes(20));
        when(encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(eq(ids[0]), eq(ids[1]), any()))
            .thenReturn(List.of(today));
        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder().temperatureCelsius(37.0).build();

        service.captureVitals(ids[0], UUID.randomUUID(), ids[1], req);

        verify(vitalSignRepository).save(any());
    }

    @Test
    void d525_aTaskIsRefusedForADischargedPatient() {
        // QA D525: POST /nurse/tasks for the discharged patient was 200.
        UUID[] ids = registeredPatient();
        com.example.hms.model.Admission closed = stay(com.example.hms.enums.AdmissionStatus.DISCHARGED);
        closed.setActualDischargeDateTime(LocalDateTime.now().minusHours(1));
        when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(eq(ids[0]), eq(ids[1]), any()))
            .thenReturn(List.of(closed));
        when(encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(eq(ids[0]), eq(ids[1]), any()))
            .thenReturn(List.of());
        NurseTaskCreateRequestDTO req = new NurseTaskCreateRequestDTO();
        req.setPatientId(ids[0]);
        req.setCategory("WOUND_CARE");
        req.setDescription("QA R8 after discharge");
        req.setPriority("ROUTINE");
        req.setDueAt(LocalDateTime.now().plusHours(1));
        UUID nurseId = UUID.randomUUID();

        assertThatThrownBy(() -> service.createNursingTask(nurseId, ids[1], req))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.task.patientDischarged");
        verify(nursingTaskRepository, never()).save(any());
    }

    @Test
    void d525_aDoseAfterDischargeGivesTheDischargeAsTheReason() {
        // QA D525: PUT /nurse/medications/mar/{rx}/administer GIVEN after the
        // discharge was refused with a timing reason (not yet due).
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q12h");
        rx.setStatus(PrescriptionStatus.SIGNED);
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        Admission closed = new Admission();
        closed.setAdmissionDateTime(rx.getCreatedAt().minusHours(1));
        closed.setActualDischargeDateTime(LocalDateTime.now().minusMinutes(5));
        when(admissionRepository.findByPatient_IdAndHospital_Id(rx.getPatient().getId(), hospitalId))
            .thenReturn(List.of(closed));
        UUID rxId = rx.getId();
        UUID nurseId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, given))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.patientDischarged");
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }
}
