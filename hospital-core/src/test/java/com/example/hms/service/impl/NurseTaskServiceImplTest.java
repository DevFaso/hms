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
    @Mock
    private com.example.hms.repository.PatientAllergyRepository patientAllergyRepository;
    @Mock
    private com.example.hms.service.emar.ControlledAdministrationWitness administrationWitness;
    /** QA D1314: reads the ward at once; here it answers from the per-patient stubs these tests set (see setUp). */
    @Mock
    private com.example.hms.service.emar.MarWardReader marWardReader;
    /** QA D1286: real, over the real bundles, so the inbox renders a keyed item for its reader. */
    private final com.example.hms.service.i18n.NotificationLocalizer notificationLocalizer =
        new com.example.hms.service.i18n.NotificationLocalizer(com.example.hms.i18n.TestMessageSources.bundles());

    @BeforeEach
    void setUp() {
        service = spy(new NurseTaskServiceImpl(
            nurseDashboardService, prescriptionRepository, marRepository,
            vitalSignRepository, announcementRepository, staffRepository, hospitalRepository,
            admissionRepository, encounterRepository, patientRepository, nursingTaskRepository,
            nursingNoteRepository, pharmacistVerificationService, notificationRepository, userRepository,
            nurseHandoffRepository, labOrderRepository, imagingOrderRepository, procedureOrderRepository,
            fiveRightsService, objectMapper, new com.example.hms.service.vitals.VitalSignJudgement(
            org.mockito.Mockito.mock(com.example.hms.repository.NewbornAssessmentRepository.class), news2EscalationService,
            org.mockito.Mockito.mock(com.example.hms.service.NotificationService.class),
            com.example.hms.i18n.TestMessageSources.bundles()),
            new com.example.hms.service.support.DischargedPatientGuard(admissionRepository, encounterRepository),
            assignmentRepository, patientAllergyRepository, administrationWitness, notificationLocalizer, marWardReader));

        // QA D220 narrowed the vitals rounds to admitted patients; the tests
        // below that are about windows, names and counts treat every listed
        // patient as admitted. The D220 tests call the real method.
        lenient().doReturn(EVERY_PATIENT).when(service).activelyAdmittedPatientIds(any());
        lenient().doReturn(EVERY_PATIENT_IN_A_STAY).when(service).activeStaysByPatient(any());

        wardReadsAnswerFromPerPatientStubs();

        // Default stubs so synthetic/fallback paths activate in existing tests
        lenient().when(vitalSignRepository.findLatestObservationSet(any(), any()))
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

    /**
     * QA D1314: the ward's lists read everything at once, through
     * {@code MarWardReader} and {@code getPatientNamesForNurse}. These tests are
     * about what a row says, so they keep stating their data per patient and per
     * order; each batch read answers from those stubs. The batch queries
     * themselves run on PostgreSQL in {@code WardLoadPostgresIT}.
     */
    private void wardReadsAnswerFromPerPatientStubs() {
        lenient().when(nurseDashboardService.getPatientNamesForNurse(any(), any())).thenAnswer(inv ->
            nurseDashboardService.getPatientsForNurse(inv.getArgument(0), inv.getArgument(1), null));
        lenient().when(marWardReader.ordersByPatient(any(), any())).thenAnswer(inv -> {
            java.util.Map<UUID, List<Prescription>> byPatient = new java.util.HashMap<>();
            UUID hospital = inv.getArgument(0);
            if (hospital == null) {
                return byPatient;
            }
            for (UUID patient : inv.<java.util.Collection<UUID>>getArgument(1)) {
                List<Prescription> rows = prescriptionRepository.findByPatient_IdAndHospital_Id(patient, hospital);
                if (!rows.isEmpty()) {
                    byPatient.put(patient, rows);
                }
            }
            return byPatient;
        });
        lenient().when(marWardReader.closedStaysByPatient(any(), any())).thenAnswer(inv -> {
            java.util.Map<UUID, List<Admission>> byPatient = new java.util.HashMap<>();
            for (UUID patient : inv.<java.util.Collection<UUID>>getArgument(1)) {
                byPatient.put(patient, admissionRepository.findByPatient_IdAndHospital_Id(patient, inv.getArgument(0)).stream()
                    .filter(a -> a.getActualDischargeDateTime() != null).toList());
            }
            return byPatient;
        });
        lenient().when(marWardReader.doseRowsByPrescription(any())).thenAnswer(inv -> {
            java.util.Map<UUID, List<MedicationAdministrationRecord>> byOrder = new java.util.HashMap<>();
            for (UUID order : inv.<java.util.Collection<UUID>>getArgument(0)) {
                byOrder.put(order, marRepository.findByPrescription_IdOrderByScheduledTimeDesc(order));
            }
            return byOrder;
        });
        lenient().when(marWardReader.allergiesByPatient(any())).thenAnswer(inv -> {
            java.util.Map<UUID, List<com.example.hms.model.PatientAllergy>> byPatient = new java.util.HashMap<>();
            for (UUID patient : inv.<java.util.Collection<UUID>>getArgument(0)) {
                byPatient.put(patient, patientAllergyRepository.findByPatient_Id(patient));
            }
            return byPatient;
        });
        lenient().when(marWardReader.latestObservationSets(any(), any())).thenAnswer(inv -> {
            java.util.Map<UUID, PatientVitalSign> byPatient = new java.util.HashMap<>();
            for (UUID patient : inv.<java.util.Collection<UUID>>getArgument(1)) {
                vitalSignRepository.findLatestObservationSet(patient, inv.getArgument(0))
                    .ifPresent(vitals -> byPatient.put(patient, vitals));
            }
            return byPatient;
        });
        lenient().when(marWardReader.controlledOrders(any())).thenAnswer(inv -> {
            java.util.Set<UUID> controlled = new java.util.HashSet<>();
            for (Prescription order : inv.<java.util.Collection<Prescription>>getArgument(0)) {
                if (pharmacistVerificationService.isControlledSubstance(order)) {
                    controlled.add(order.getId());
                }
            }
            return controlled;
        });
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

    /** QA D1384 (round 32): an unknown handoff status filter is refused on "status" (fieldErrors), translated. */
    @Test
    void d1384_anUnknownHandoffStatusIsRefusedOnTheParameter() {
        UUID hospitalId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getHandoffSummaries(null, null, hospitalId, 10, "BOGUS"))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("status"));
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
        // QA D1075: the report time is the creation time, which completion never moves.
        first.setCreatedAt(java.time.LocalDateTime.of(2026, 10, 30, 13, 33, 43));
        first.setUpdatedAt(java.time.LocalDateTime.of(2026, 10, 30, 13, 34, 2));
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
        assertThat(dto.getCreatedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 30, 13, 33, 43));
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
        when(vitalSignRepository.findLatestObservationSet(patientId, hospitalId))
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
            .hasFieldOrPropertyWithValue("messageKey", "nurse.handoff.recipient.required")
            // QA D1282: the refusal names the field, so the handoff form marks the recipient picker.
            .hasFieldOrPropertyWithValue("field", "recipientUserId");
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
            .hasFieldOrPropertyWithValue("messageKey", "nurse.handoff.recipient.notSelf")
            // QA D1352 (round 30): on the recipient picker.
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "recipientUserId");
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
        // QA D1159: a held dose says why.
        request.setNote("Patient au bloc");

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
        when(vitalSignRepository.findLatestObservationSet(patientId, hospitalId))
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
        when(vitalSignRepository.findLatestObservationSet(patientId, hospitalId))
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
        // QA D1491 (changed: no frequency used to close the course): a single dose is what closes it.
        lenient().when(rx.getFrequency()).thenReturn("STAT");
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
    void D922_aControlledDoseIsRecordedWithItsWitnessAndRefusedWithoutOne() {
        UUID rxId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID witnessId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(UUID.randomUUID());
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);
        Prescription rx = mock(Prescription.class);
        lenient().when(rx.getId()).thenReturn(rxId);
        lenient().when(rx.getPatient()).thenReturn(mockPatient);
        lenient().when(rx.getHospital()).thenReturn(mockHospital);
        lenient().when(rx.getMedicationName()).thenReturn("Morphine");
        lenient().when(rx.isControlledSubstance()).thenReturn(true);
        // A controlled order is verified by a pharmacist before it is given (the gate above the witness).
        lenient().when(rx.getPharmacistVerifiedAt()).thenReturn(LocalDateTime.now().minusHours(1));
        lenient().when(rx.getCreatedAt()).thenReturn(LocalDateTime.now().minusHours(1));
        when(prescriptionRepository.findByIdForUpdate(rxId)).thenReturn(Optional.of(rx));
        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");
        request.setOverrideReason("Scanner down; checked by hand.");

        // Without a witness: refused before anything is written.
        when(administrationWitness.resolve(rx, MedicationAdministrationStatus.GIVEN, null, nurseId))
            .thenThrow(new BusinessException("nurse.mar.witness.required", "Morphine"));
        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, request))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.witness.required");
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));

        // With a colleague as witness: recorded with the dose.
        User colleague = new User();
        colleague.setId(witnessId);
        colleague.setFirstName("Awa");
        colleague.setLastName("Kaboré");
        request.setWitnessUserId(witnessId);
        when(administrationWitness.resolve(rx, MedicationAdministrationStatus.GIVEN, witnessId, nurseId))
            .thenReturn(colleague);
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        NurseMedicationTaskResponseDTO result = service.recordMedicationAdministration(rxId, nurseId, hospitalId, request);

        org.mockito.ArgumentCaptor<MedicationAdministrationRecord> saved =
            org.mockito.ArgumentCaptor.forClass(MedicationAdministrationRecord.class);
        verify(marRepository).save(saved.capture());
        assertThat(saved.getValue().getWitnessUser()).isSameAs(colleague);
        assertThat(result.getWitnessName()).isEqualTo("Awa Kaboré");
        // QA D1080: the administer response says what the GET says - morphine is controlled.
        assertThat(result.isControlledSubstance()).isTrue();
    }

    /** QA D1080: the synthetic-task fallback keeps the row's controlled flag too. */
    @Test
    void d1080_theFallbackResponseKeepsTheControlledFlag() {
        UUID taskId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        doReturn(List.of(NurseMedicationTaskResponseDTO.builder().id(taskId).patientId(UUID.randomUUID())
            .medication("Morphine").status("DUE").controlledSubstance(true).build()))
            .when(service).getMedicationTasks(nurseId, hospitalId, null);

        assertThat(service.recordMedicationAdministration(taskId, nurseId, hospitalId, null).isControlledSubstance())
            .isTrue();
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
        when(vitalSignRepository.findLatestObservationSet(patientId, hospitalId))
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
        when(vitalSignRepository.findLatestObservationSet(patientId, hospitalId))
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

            // QA D1402: the tile is the list's row count. Round 33: a set an hour old has its
            // next round an hour away, past the 30-minute look-ahead, so it is no row (it was one
            // while the look-ahead was also the 2-hour interval: nearly every patient was due).
            assertThat(summary.getVitalsDue()).isZero();
            assertThat(summary.getMedicationsOverdue()).isEqualTo(1);
            assertThat(summary.getMedicationsDue()).isZero();
            assertThat(summary.getAnnouncements()).isZero();
        }
    }

    /**
     * QA round 30: an as-needed order is given on demand, not waiting: the
     * dashboard counts it neither due nor overdue (a PRN never given read
     * OVERDUE an hour after it was signed, and counted).
     */
    @Test
    void prn_anAsNeededOrderIsNotCountedDueOrOverdue() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2025, 10, 30, 10, 0);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Prn Pat", "Prn", "Pat")));
        when(vitalSignRepository.findLatestObservationSet(patientId, hospitalId)).thenReturn(Optional.empty());
        Patient mockPatient = mock(Patient.class);
        lenient().when(mockPatient.getId()).thenReturn(patientId);
        Hospital mockHospital = mock(Hospital.class);
        lenient().when(mockHospital.getId()).thenReturn(hospitalId);
        Prescription prn = mock(Prescription.class);
        lenient().when(prn.getId()).thenReturn(UUID.randomUUID());
        lenient().when(prn.getPatient()).thenReturn(mockPatient);
        lenient().when(prn.getHospital()).thenReturn(mockHospital);
        when(prn.getStatus()).thenReturn(PrescriptionStatus.SIGNED);
        lenient().when(prn.getCreatedAt()).thenReturn(fixedNow.minusHours(8));
        lenient().when(prn.getFrequency()).thenReturn("si besoin");
        // QA D1378: as-needed is the prescriber's tick.
        when(prn.isAsNeeded()).thenReturn(true);
        lenient().when(prn.getPrnMinIntervalHours()).thenReturn(6);
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(prn));
        when(announcementRepository.countByHospital_Id(hospitalId)).thenReturn(0L);

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);
            NurseDashboardSummaryDTO summary = service.getDashboardSummary(nurseId, hospitalId);
            assertThat(summary.getMedicationsDue()).isZero();
            assertThat(summary.getMedicationsOverdue()).isZero();
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
        when(vitalSignRepository.findLatestObservationSet(patientId, hospitalId))
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
        when(vitalSignRepository.findLatestObservationSet(patientId, hospitalId))
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
        // QA D1402: written during this stay. The card counts what the eMAR lists;
        // an order older than the admission is not one of its doses (QA D702).
        activePending.setCreatedAt(LocalDateTime.now().minusHours(4));

        when(admissionRepository.findActiveAdmissionsByHospital(hospitalId)).thenReturn(List.of(adm));
        when(vitalSignRepository.findLatestObservationSet(patientId, hospitalId))
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

    /** QA D1077: the nurse station refuses a head circumference for an adult, before any row is saved. */
    @Test
    void captureVitalsRefusesAHeadCircumferenceForAnAdult_D1077() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient pat = Patient.builder().build();
        pat.setId(patientId);
        pat.setDateOfBirth(java.time.LocalDate.now().minusYears(46));
        Hospital hosp = Hospital.builder().build();
        hosp.setId(hospitalId);
        registerPatientAtHospital(pat, hosp);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(pat));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hosp));
        NurseVitalCaptureRequestDTO req = NurseVitalCaptureRequestDTO.builder()
            .heartRateBpm(72).headCircumferenceCm(57.0).build();

        UUID nurseId = UUID.randomUUID();
        assertThatThrownBy(() -> service.captureVitals(patientId, nurseId, hospitalId, req))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("vitals.headCircumference.notApplicable");
        verify(vitalSignRepository, never()).save(any());
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
            .painScale(4)
            .build();

        service.captureVitals(patientId, nurseId, hospitalId, req);

        ArgumentCaptor<PatientVitalSign> captor = ArgumentCaptor.forClass(PatientVitalSign.class);
        verify(vitalSignRepository).save(captor.capture());
        PatientVitalSign saved = captor.getValue();
        assertThat(saved.getHeartRateBpm()).isEqualTo(72);
        // QA D1045: the ward pain reassessment is charted. QA D1169: as a number, rendered
        // in the reader's language when read (it was stored as the nurse's sentence).
        assertThat(saved.getPainScore()).isEqualTo(4);
        assertThat(saved.getNotes()).isNull();
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

    /** QA D1564 (round 43): a 4-day-old's HR 80 passed the adult limits; the newborn bands flag it. */
    @Test
    void captureVitalsJudgesANewbornWithTheNewbornBands_D1564() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient baby = Patient.builder().build();
        baby.setId(patientId);
        baby.setDateOfBirth(java.time.LocalDate.now().minusDays(4));
        Hospital hospital = Hospital.builder().build();
        hospital.setId(hospitalId);
        registerPatientAtHospital(baby, hospital);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(baby));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));

        com.example.hms.payload.dto.NurseVitalCaptureResponseDTO response = service.captureVitals(patientId,
            UUID.randomUUID(), hospitalId, NurseVitalCaptureRequestDTO.builder().heartRateBpm(80).build());

        ArgumentCaptor<PatientVitalSign> captor = ArgumentCaptor.forClass(PatientVitalSign.class);
        verify(vitalSignRepository).save(captor.capture());
        assertThat(captor.getValue().isClinicallySignificant()).isTrue();
        assertThat(response.isClinicallySignificant()).isTrue();
        assertThat(response.getNewbornAlerts()).extracting(com.example.hms.payload.dto.NewbornVitalAlertDTO::getCode)
            .contains("newborn-heart-rate");
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

        when(notificationRepository.findByRecipientUsernameAndReadOrderByCreatedAtDesc("nurse1", false))
            .thenReturn(List.of(n));
        when(notificationRepository.findByRecipientUsernameAndRead(eq("nurse1"), eq(true), any(Pageable.class)))
            .thenReturn(Page.empty());

        List<NurseInboxItemDTO> result = service.getNurseInboxItems("nurse1", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessage()).isEqualTo("New order");
        assertThat(result.get(0).isRead()).isFalse();
    }

    /**
     * QA D1295 (round 28, patient safety): the inbox read 20 rows with no
     * order and cut today's NEWS2 16 escalation. Every unread item comes back,
     * however many, newest first; the limit bounds only the read history,
     * which follows, asked newest first from the database.
     */
    @Test
    void getNurseInboxItemsReturnsEveryUnreadItemNewestFirstThenTheReadHistory_D1295() {
        LocalDateTime now = LocalDateTime.now();
        List<Notification> unread = new java.util.ArrayList<>();
        for (int i = 0; i < 41; i++) {
            unread.add(Notification.builder().id(UUID.randomUUID()).message("unread " + i).read(false)
                .createdAt(now.minusMinutes(i)).recipientUsername("nurse.a").build());
        }
        Notification readOne = Notification.builder().id(UUID.randomUUID()).message("seen").read(true)
            .createdAt(now.plusMinutes(1)).recipientUsername("nurse.a").build();
        when(notificationRepository.findByRecipientUsernameAndReadOrderByCreatedAtDesc("nurse.a", false))
            .thenReturn(unread);
        org.mockito.ArgumentCaptor<Pageable> asked = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        when(notificationRepository.findByRecipientUsernameAndRead(eq("nurse.a"), eq(true), asked.capture()))
            .thenReturn(new PageImpl<>(List.of(readOne)));

        List<NurseInboxItemDTO> result = service.getNurseInboxItems("nurse.a", 20);

        assertThat(result).hasSize(42);
        assertThat(result.get(0).getMessage()).isEqualTo("unread 0");
        assertThat(result.subList(0, 41)).noneMatch(NurseInboxItemDTO::isRead);
        assertThat(result.get(41).getMessage()).isEqualTo("seen");
        assertThat(asked.getValue().getPageSize()).isEqualTo(20);
        assertThat(asked.getValue().getSort()).isEqualTo(NurseTaskServiceImpl.INBOX_NEWEST_FIRST);
        assertThat(asked.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    /**
     * QA D1286 (round 27): the NEWS2 escalation is stored with its French copy
     * and its key; ES and EN nurses read it in French. Each reader now gets it
     * rendered from the key in their language; a keyless row keeps its text.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "es|Puntuación NEWS2 7 (ALTA) para Drissa Konaté",
        "en|NEWS2 score 7",
        "fr|Score NEWS2 7 (ÉLEVÉ) pour Drissa Konaté",
    })
    void getNurseInboxItemsRendersAKeyedItemInTheNursesLanguage(String language, String expected) {
        Notification news2 = Notification.builder()
            .id(UUID.randomUUID()).message("Score NEWS2 7 (ÉLEVÉ) pour Drissa Konaté : …")
            .messageKey("news2.escalation.high").messageArgs(List.of(7, "Drissa Konaté", 15))
            .createdAt(LocalDateTime.now()).recipientUsername("nurse.b").build();
        when(notificationRepository.findByRecipientUsernameAndReadOrderByCreatedAtDesc("nurse.b", false))
            .thenReturn(List.of(news2));
        when(notificationRepository.findByRecipientUsernameAndRead(eq("nurse.b"), eq(true), any(Pageable.class)))
            .thenReturn(Page.empty());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag(language));
        try {
            assertThat(service.getNurseInboxItems("nurse.b", 10).get(0).getMessage()).startsWith(expected);
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
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
        req.setTitle("Réévaluation douleur post-morphine");

        NurseCareNoteResponseDTO result = service.createCareNote(patientId, nurseId, hospitalId, req);

        // QA D1044: the title the nurse typed is stored with the note and returned.
        ArgumentCaptor<NursingNote> savedNote = ArgumentCaptor.forClass(NursingNote.class);
        verify(nursingNoteRepository).save(savedNote.capture());
        assertThat(savedNote.getValue().getTitle()).isEqualTo("Réévaluation douleur post-morphine");
        // QA D1107: the dialog has no draft state; the saved note is final, signed by its author.
        assertThat(savedNote.getValue().getSignedAt()).isEqualTo(savedNote.getValue().getDocumentedAt()).isNotNull();
        assertThat(savedNote.getValue().getSignedByName()).isEqualTo("Jane RN");
        assertThat(result.getTitle()).isEqualTo("Réévaluation douleur post-morphine");
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
        rx.setStatus(PrescriptionStatus.SIGNED);
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

    /* ── QA D799: the eMAR carries the beta-lactam cross-reactivity advisory ── */

    private static com.example.hms.model.PatientAllergy penicillinAllergy() {
        com.example.hms.model.PatientAllergy allergy = new com.example.hms.model.PatientAllergy();
        allergy.setActive(true);
        allergy.setAllergenDisplay("Pénicilline");
        return allergy;
    }

    /**
     * QA D1563: the catalogue the allergy check reads names through. A name no item, brand or INN
     * stem explains meets the safety net for a patient with a class allergy, as at prescribing.
     */
    private void catalogueKnows(String... genericNames) {
        com.example.hms.repository.MedicationCatalogItemRepository repo =
            mock(com.example.hms.repository.MedicationCatalogItemRepository.class);
        List<com.example.hms.model.medication.MedicationCatalogItem> items = java.util.Arrays.stream(genericNames)
            .map(n -> com.example.hms.model.medication.MedicationCatalogItem.builder().genericName(n).nameFr(n).build())
            .toList();
        lenient().when(repo.findActivePage(any(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(items));
        service.setAllergyCheck(new com.example.hms.service.allergy.CurrentAllergyRecheck(patientAllergyRepository, repo, null));
    }

    private static com.example.hms.model.medication.MedicationCatalogItem catalogItem(String genericName) {
        return com.example.hms.model.medication.MedicationCatalogItem.builder().genericName(genericName).nameFr(genericName).build();
    }

    private Prescription signedOrder(UUID patientId, UUID hospitalId, String drug, String dose, String route,
                                     LocalDateTime createdAt) {
        Patient patient = new Patient();
        patient.setId(patientId);
        Hospital hospital = mock(Hospital.class);
        lenient().when(hospital.getId()).thenReturn(hospitalId);
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setPatient(patient);
        rx.setHospital(hospital);
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setMedicationName(drug);
        rx.setDosage(dose);
        rx.setRoute(route);
        rx.setFrequency("Toutes les 24 heures");
        rx.setCreatedAt(createdAt);
        return rx;
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D799: the MAR row of ceftriaxone for a penicillin-allergic patient carries the cross-reactivity WARNING")
    void marRowOfCeftriaxoneForPenicillinAllergyCarriesTheAdvisory() {
        // The re-test call: NURSE_A GET /nurse/medications/mar; row e2337828
        // (Ceftriaxone 1 g IV, CHW7809 allergic to penicillin) had no field.
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Boureima Y", "Boureima", "Y")));
        Prescription cef = signedOrder(patientId, hospitalId, "Ceftriaxone", "1 g", "IV", LocalDateTime.now().minusMinutes(5));
        Prescription para = signedOrder(patientId, hospitalId, "Paracétamol", "1 g", "PO", LocalDateTime.now().minusMinutes(5));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(cef, para));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(penicillinAllergy()));
        catalogueKnows("Paracétamol", "Ceftriaxone");

        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);

        NurseMedicationTaskResponseDTO cefRow = tasks.stream().filter(t -> "Ceftriaxone".equals(t.getMedication())).findFirst().orElseThrow();
        assertThat(cefRow.getCdsAdvisories()).hasSize(1);
        com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard card = cefRow.getCdsAdvisories().get(0);
        assertThat(card.indicator()).isEqualTo(com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard.Indicator.WARNING);
        assertThat(card.summary()).contains("Ceftriaxone").contains("Pénicilline");
        // No false alarm on the paracetamol row, and one allergy read per patient.
        NurseMedicationTaskResponseDTO paraRow = tasks.stream().filter(t -> "Paracétamol".equals(t.getMedication())).findFirst().orElseThrow();
        assertThat(paraRow.getCdsAdvisories()).isEmpty();
        verify(patientAllergyRepository, times(1)).findByPatient_Id(patientId);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D799: the bedside verify of that dose passes the five rights AND returns the advisory")
    void verifyOfCeftriaxoneForPenicillinAllergyReturnsTheAdvisoryWithoutFailingARight() {
        // The re-test call: POST /nurse/medications/mar/{id}/verify with the right
        // scans returned 200 allPassed=true and no warning.
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription cef = signedOrder(patientId, hospitalId, "Ceftriaxone", "1 g", "IV", LocalDateTime.now());
        when(marRepository.findById(cef.getId())).thenReturn(Optional.empty());
        when(prescriptionRepository.findByIdForUpdate(cef.getId())).thenReturn(Optional.of(cef));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(penicillinAllergy()));

        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue(patientId.toString());
        req.setMedicationScanValue("Ceftriaxone");
        req.setDoseScanValue("1 g");
        req.setRouteScanValue("IV");

        MarVerificationResponseDTO resp = service.verifyMedicationAdministration(cef.getId(), null, hospitalId, req);

        assertThat(resp.isAllPassed()).isTrue();
        assertThat(resp.getCdsAdvisories()).hasSize(1);
        assertThat(resp.getCdsAdvisories().get(0).indicator())
            .isEqualTo(com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard.Indicator.WARNING);
        assertThat(resp.getCdsAdvisories().get(0).summary()).contains("Ceftriaxone");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D799: no allergy, no advisory on the verify (negative control, patient A2)")
    void verifyWithoutAllergyCarriesNoAdvisory() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription cef = signedOrder(patientId, hospitalId, "Ceftriaxone", "1 g", "IV", LocalDateTime.now());
        when(marRepository.findById(cef.getId())).thenReturn(Optional.empty());
        when(prescriptionRepository.findByIdForUpdate(cef.getId())).thenReturn(Optional.of(cef));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue(patientId.toString());
        req.setMedicationScanValue("Ceftriaxone");
        req.setDoseScanValue("1 g");
        req.setRouteScanValue("IV");

        MarVerificationResponseDTO resp = service.verifyMedicationAdministration(cef.getId(), null, hospitalId, req);

        assertThat(resp.isAllPassed()).isTrue();
        assertThat(resp.getCdsAdvisories()).isEmpty();
    }

    /* ── QA D1563: a drug the patient is allergic to is checked again at the dose ── */

    private static com.example.hms.model.PatientAllergy severePenicillinAllergy() {
        com.example.hms.model.PatientAllergy allergy = penicillinAllergy();
        allergy.setId(UUID.randomUUID());
        allergy.setSeverity(com.example.hms.enums.AllergySeverity.SEVERE);
        allergy.setReaction("Angioedema");
        return allergy;
    }

    private static NurseMedicationAdministrationRequestDTO givenWithOverride() {
        NurseMedicationAdministrationRequestDTO req = new NurseMedicationAdministrationRequestDTO();
        req.setStatus("GIVEN");
        req.setOverrideReason("Scanner down; checked by hand.");
        return req;
    }

    private static com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard criticalCard(
        List<com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard> cards
    ) {
        return cards.stream()
            .filter(c -> c.indicator() == com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard.Indicator.CRITICAL)
            .findFirst().orElseThrow();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D1563: the MAR row of amoxicillin for a patient allergic to penicillin carries the CRITICAL allergy card")
    void d1563_theMarRowOfADrugThePatientIsAllergicToCarriesTheAllergyCard() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Awa K", "Awa", "K")));
        Prescription amox = signedOrder(patientId, hospitalId, "Amoxicilline", "500 mg", "PO", LocalDateTime.now().minusMinutes(5));
        Prescription para = signedOrder(patientId, hospitalId, "Paracétamol", "500 mg", "PO", LocalDateTime.now().minusMinutes(5));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(amox, para));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(severePenicillinAllergy()));
        catalogueKnows("Paracétamol", "Amoxicilline");

        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);

        NurseMedicationTaskResponseDTO amoxRow = tasks.stream().filter(t -> "Amoxicilline".equals(t.getMedication())).findFirst().orElseThrow();
        com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard card = criticalCard(amoxRow.getCdsAdvisories());
        assertThat(card.summary()).contains("Amoxicilline").contains("Pénicilline");
        assertThat(card.exceptionKey()).startsWith(com.example.hms.service.allergy.CurrentAllergyRecheck.KEY_PREFIX);
        NurseMedicationTaskResponseDTO paraRow = tasks.stream().filter(t -> "Paracétamol".equals(t.getMedication())).findFirst().orElseThrow();
        assertThat(paraRow.getCdsAdvisories()).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D1563: the bedside verify of that dose returns the allergy card")
    void d1563_theBedsideVerifyReturnsTheAllergyCard() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription amox = signedOrder(patientId, hospitalId, "Amoxicilline", "500 mg", "PO", LocalDateTime.now());
        when(prescriptionRepository.findByIdForUpdate(amox.getId())).thenReturn(Optional.of(amox));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(severePenicillinAllergy()));

        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue(patientId.toString());
        req.setMedicationScanValue("Amoxicilline");
        req.setDoseScanValue("500 mg");
        req.setRouteScanValue("PO");

        MarVerificationResponseDTO resp = service.verifyMedicationAdministration(amox.getId(), null, hospitalId, req);

        assertThat(criticalCard(resp.getCdsAdvisories()).summary()).contains("Pénicilline");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D1563: GIVEN without acknowledging the allergy is refused on its field, nothing written")
    void d1563_givenWithoutAcknowledgingTheAllergyIsRefused() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription amox = signedOrder(patientId, hospitalId, "Amoxicilline", "500 mg", "PO", LocalDateTime.now());
        when(prescriptionRepository.findByIdForUpdate(amox.getId())).thenReturn(Optional.of(amox));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(severePenicillinAllergy()));

        assertThatThrownBy(() -> service.recordMedicationAdministration(amox.getId(), null, hospitalId, givenWithOverride()))
            .isInstanceOf(com.example.hms.cdshooks.CdsCriticalBlockException.class)
            .satisfies(e -> {
                com.example.hms.cdshooks.CdsCriticalBlockException block = (com.example.hms.cdshooks.CdsCriticalBlockException) e;
                assertThat(block.getField()).isEqualTo(com.example.hms.service.allergy.CurrentAllergyRecheck.ACK_FIELD);
                assertThat(block.getCards()).singleElement()
                    .satisfies(c -> assertThat(c.exceptionKey()).startsWith(com.example.hms.service.allergy.CurrentAllergyRecheck.KEY_PREFIX));
            });
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D1563: GIVEN with the allergy acknowledged, with the card's token and a reason, is recorded with the reason")
    void d1563_givenWithTheAllergyAcknowledgedIsRecordedWithTheReason() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription amox = signedOrder(patientId, hospitalId, "Amoxicilline", "500 mg", "PO", LocalDateTime.now());
        when(prescriptionRepository.findByIdForUpdate(amox.getId())).thenReturn(Optional.of(amox));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(severePenicillinAllergy()));
        String token;
        try {
            service.recordMedicationAdministration(amox.getId(), null, hospitalId, givenWithOverride());
            throw new AssertionError("the first GIVEN should have been refused");
        } catch (com.example.hms.cdshooks.CdsCriticalBlockException refused) {
            token = refused.getCards().get(0).exceptionKey();
        }

        NurseMedicationAdministrationRequestDTO req = givenWithOverride();
        req.setAllergyAcknowledgementReason("Prescriber called: reaction was a rash, re-challenge ordered");
        req.setAllergyAcknowledgementToken(token);
        service.recordMedicationAdministration(amox.getId(), null, hospitalId, req);

        ArgumentCaptor<MedicationAdministrationRecord> captor = ArgumentCaptor.forClass(MedicationAdministrationRecord.class);
        verify(marRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(MedicationAdministrationStatus.GIVEN);
        assertThat(captor.getValue().getNotes()).contains("Pénicilline").contains("re-challenge ordered");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D1563: a reason with no token, or a token for another allergy state, is refused as stale")
    void d1563_aReasonWithoutTheCardsTokenIsRefused() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription amox = signedOrder(patientId, hospitalId, "Amoxicilline", "500 mg", "PO", LocalDateTime.now());
        when(prescriptionRepository.findByIdForUpdate(amox.getId())).thenReturn(Optional.of(amox));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(severePenicillinAllergy()));

        NurseMedicationAdministrationRequestDTO req = givenWithOverride();
        req.setAllergyAcknowledgementReason("Seen");
        req.setAllergyAcknowledgementToken("allergy:not-this-one");

        assertThatThrownBy(() -> service.recordMedicationAdministration(amox.getId(), null, hospitalId, req))
            .isInstanceOf(com.example.hms.cdshooks.CdsCriticalBlockException.class)
            .hasFieldOrPropertyWithValue("field", com.example.hms.service.allergy.CurrentAllergyRecheck.ACK_FIELD)
            // The stale refusal names the allergy, not the card (the "required" one quotes the card, drug included).
            .hasMessageContaining("Pénicilline")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Amoxicilline"));
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D1563: a HELD dose of that drug is always recorded, no acknowledgement asked")
    void d1563_aHeldDoseIsAlwaysRecorded() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription amox = signedOrder(patientId, hospitalId, "Amoxicilline", "500 mg", "PO", LocalDateTime.now());
        when(prescriptionRepository.findByIdForUpdate(amox.getId())).thenReturn(Optional.of(amox));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(severePenicillinAllergy()));

        NurseMedicationAdministrationRequestDTO req = new NurseMedicationAdministrationRequestDTO();
        req.setStatus("HELD");
        req.setNote("Allergy found on the chart; prescriber called");
        service.recordMedicationAdministration(amox.getId(), null, hospitalId, req);

        verify(marRepository).save(argThat(m -> m.getStatus() == MedicationAdministrationStatus.HELD));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D1563: a dose already verified at the bedside (the MAR-row path) is refused the same way")
    void d1563_theVerifiedRowPathIsRefusedTheSameWay() {
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
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(severePenicillinAllergy()));

        NurseMedicationAdministrationRequestDTO req = new NurseMedicationAdministrationRequestDTO();
        req.setStatus("GIVEN");

        assertThatThrownBy(() -> service.recordMedicationAdministration(marId, null, hospitalId, req))
            .isInstanceOf(com.example.hms.cdshooks.CdsCriticalBlockException.class)
            .hasFieldOrPropertyWithValue("field", com.example.hms.service.allergy.CurrentAllergyRecheck.ACK_FIELD);
        assertThat(mar.getStatus()).isEqualTo(MedicationAdministrationStatus.PENDING);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D1563: a brand is read as its class (Clamoxyl for penicillin), and a name nobody knows meets the safety net")
    void d1563_aBrandIsReadAsItsClass() {
        List<com.example.hms.model.PatientAllergy> allergies = List.of(severePenicillinAllergy());
        UUID order = UUID.randomUUID();
        assertThat(com.example.hms.service.allergy.CurrentAllergyRecheck.stop(order, "Clamoxyl 1 g", List.of(), allergies))
            .get().extracting(com.example.hms.service.allergy.CurrentAllergyRecheck.Stop::kind).isEqualTo("documented");
        assertThat(com.example.hms.service.allergy.CurrentAllergyRecheck.stop(order, "Zxqvorin", List.of(), allergies))
            .get().extracting(com.example.hms.service.allergy.CurrentAllergyRecheck.Stop::kind).isEqualTo("unverifiable");
        assertThat(com.example.hms.service.allergy.CurrentAllergyRecheck.stop(order, "Paracétamol",
            List.of(catalogItem("Paracétamol")), allergies)).isEmpty();
        // Ceftriaxone stays the cross-reactivity WARNING, never a stop.
        assertThat(com.example.hms.service.allergy.CurrentAllergyRecheck.stop(order, "Ceftriaxone", List.of(), allergies)).isEmpty();
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
        rx.setStatus(PrescriptionStatus.SIGNED);
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
        // QA D1159: a held or refused dose says why.
        request.setNote("Patient nauséeux");
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"HELD", "REFUSED"})
    void aWithheldDoseWithoutAReasonIsRefusedBeforeAnythingIsWritten_D1159(String status) {
        // QA D1159 (round 21): the eMAR sent {"status":"HELD"} alone and the record kept no reason.
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = highRiskRx(hospitalId, false);
        org.mockito.Mockito.lenient().when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        NurseMedicationAdministrationRequestDTO request = statusRequest(status);
        request.setNote("  ");

        assertThatThrownBy(() -> service.recordMedicationAdministration(rx.getId(), UUID.randomUUID(), hospitalId, request))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("nurse.mar.reasonRequired");
        verify(marRepository, never()).save(any());
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
            fiveRightsService, failingMapper, new com.example.hms.service.vitals.VitalSignJudgement(
            org.mockito.Mockito.mock(com.example.hms.repository.NewbornAssessmentRepository.class), news2EscalationService,
            org.mockito.Mockito.mock(com.example.hms.service.NotificationService.class),
            com.example.hms.i18n.TestMessageSources.bundles()),
            new com.example.hms.service.support.DischargedPatientGuard(admissionRepository, encounterRepository),
            assignmentRepository, patientAllergyRepository, administrationWitness, notificationLocalizer, marWardReader);

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

    /* ── QA D1210 (round 23): the minimum interval since the last GIVEN, on every write path ── */

    private MedicationAdministrationRecord givenAt(Prescription rx, LocalDateTime slot, LocalDateTime administeredAt) {
        MedicationAdministrationRecord r = closedDose(rx, slot, MedicationAdministrationStatus.GIVEN);
        r.setAdministeredAt(administeredAt);
        return r;
    }

    @Test
    void D1210_theQaMorphineQ4hDoseAMinuteAfterTheLateOneIsRefused() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q4h");
        rx.setMedicationName("Morphine 2 mg IV");
        // First slot six hours ago, given late a minute ago: the second slot is already overdue.
        rx.setCreatedAt(LocalDateTime.now().minusHours(6));
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(givenAt(rx, rx.getCreatedAt(), LocalDateTime.now().minusMinutes(1))));
        UUID rxId = rx.getId();
        UUID nurseId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, given))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.doseTooSoon")
            .hasFieldOrPropertyWithValue("field", "earlyDoseReason");
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }

    @Test
    void D1210_anEarlyDoseWithAReasonIsRecordedWithTheReasonOnTheRow() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "TID");
        rx.setCreatedAt(LocalDateTime.now().minusHours(10));
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(givenAt(rx, rx.getCreatedAt(), LocalDateTime.now().minusMinutes(2))));
        ArgumentCaptor<MedicationAdministrationRecord> saved = ArgumentCaptor.forClass(MedicationAdministrationRecord.class);
        when(marRepository.save(saved.capture())).thenAnswer(i -> i.getArgument(0));
        NurseMedicationAdministrationRequestDTO given = givenRequest();
        given.setEarlyDoseReason("Leaving for theatre; surgeon asked for the dose now");

        service.recordMedicationAdministration(rx.getId(), UUID.randomUUID(), hospitalId, given);

        assertThat(saved.getValue().getStatus()).isEqualTo(MedicationAdministrationStatus.GIVEN);
        assertThat(saved.getValue().getReason()).isEqualTo("Leaving for theatre; surgeon asked for the dose now");
    }

    @Test
    void D1210_aDoseOnTimeSinceTheLastOneNeedsNoReason() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q4h");
        rx.setCreatedAt(LocalDateTime.now().minusHours(8));
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(givenAt(rx, rx.getCreatedAt(), LocalDateTime.now().minusHours(4))));
        marSaveEchoes();

        NurseMedicationTaskResponseDTO out =
            service.recordMedicationAdministration(rx.getId(), UUID.randomUUID(), hospitalId, givenRequest());

        assertThat(out.getStatus()).isEqualTo("GIVEN");
    }

    @Test
    void D1210_theVerifiedRowPathIsHeldToTheSameInterval() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q4h");
        rx.setCreatedAt(LocalDateTime.now().minusHours(6));
        MedicationAdministrationRecord first = givenAt(rx, rx.getCreatedAt(), LocalDateTime.now().minusMinutes(1));
        MedicationAdministrationRecord verified =
            closedDose(rx, rx.getCreatedAt().plusHours(4), MedicationAdministrationStatus.PENDING);
        verified.setFiveRightsStatus(FiveRightsStatus.VERIFIED);
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(verified, first));
        UUID rxId = rx.getId();
        UUID nurseId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO given = statusRequest("GIVEN");

        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, given))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.doseTooSoon");
        assertThat(verified.getStatus()).isEqualTo(MedicationAdministrationStatus.PENDING);
    }

    @Test
    void D1210_theMarRowIdPathIsHeldToTheSameInterval() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q4h");
        rx.setCreatedAt(LocalDateTime.now().minusHours(6));
        MedicationAdministrationRecord first = givenAt(rx, rx.getCreatedAt(), LocalDateTime.now().minusMinutes(1));
        MedicationAdministrationRecord pending =
            closedDose(rx, rx.getCreatedAt().plusHours(4), MedicationAdministrationStatus.PENDING);
        when(marRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(pending, first));
        UUID marId = pending.getId();
        UUID nurseId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        assertThatThrownBy(() -> service.recordMedicationAdministration(marId, nurseId, hospitalId, given))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.doseTooSoon");
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
        // QA D949: eight hours away is not yet due (it was reported DUE and refused on Give).
        assertThat(tasks.get(0).getStatus()).isEqualTo("UPCOMING");
        assertThat(tasks.get(0).getDueTime()).isEqualTo(firstSlot.plusHours(12));
    }

    @Test
    void D77_theSameDoseCannotBeRecordedTwice() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "once only");
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

    // ── QA round 16 (D928, D929, D949, D950, D922) ──────────────────────

    private List<NurseMedicationTaskResponseDTO> marFor(UUID nurseId, UUID hospitalId, Prescription rx,
                                                        String filter,
                                                        MedicationAdministrationRecord... closed) {
        UUID patientId = rx.getPatient().getId();
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Awa Kabore", "Awa", "Kabore")));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(rx));
        lenient().when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(closed));
        return service.getMedicationTasks(nurseId, hospitalId, filter);
    }

    /**
     * QA D928 replay (u16.js): "Une fois par jour", held. The course used to
     * become COMPLETED with no due time, so days 2-5 vanished. The next dose
     * is tomorrow, and the held dose is said to be held (D929).
     */
    @Test
    void d928_aFrenchWordFrequencyKeepsTheCourseAfterAHeldDose() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "Une fois par jour");
        rx.setStatus(PrescriptionStatus.SIGNED);
        LocalDateTime firstSlot = rx.getCreatedAt();

        List<NurseMedicationTaskResponseDTO> tasks = marFor(UUID.randomUUID(), hospitalId, rx, null,
            closedDose(rx, firstSlot, MedicationAdministrationStatus.HELD));

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getStatus()).isEqualTo("UPCOMING");
        assertThat(tasks.get(0).getDueTime()).isEqualTo(firstSlot.plusHours(24));
        assertThat(tasks.get(0).getLastOutcome()).isEqualTo("HELD");
    }

    /** QA D929: a one-off dose that was refused ends the course, and the row says it was refused. */
    @Test
    void d929_aFinishedCourseCarriesTheLastOutcome() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "une seule fois");
        rx.setStatus(PrescriptionStatus.SIGNED);

        List<NurseMedicationTaskResponseDTO> tasks = marFor(UUID.randomUUID(), hospitalId, rx, null,
            closedDose(rx, rx.getCreatedAt(), MedicationAdministrationStatus.REFUSED));

        assertThat(tasks.get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(tasks.get(0).getLastOutcome()).isEqualTo("REFUSED");
    }

    /**
     * QA D949: tomorrow's dose is not "to give" and is not counted as due;
     * the eMAR's "still to give" list (PENDING) leaves it out.
     */
    @Test
    void d949_aSlotBeyondTheEarlyWindowIsUpcomingAndNotPending() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q24h");
        rx.setStatus(PrescriptionStatus.SIGNED);

        List<NurseMedicationTaskResponseDTO> pending = marFor(UUID.randomUUID(), hospitalId, rx, "PENDING",
            closedDose(rx, rx.getCreatedAt(), MedicationAdministrationStatus.GIVEN));

        assertThat(pending).isEmpty();
    }

    /** QA D950/D922: the route as a form code, and the controlled flag, on the MAR row. */
    @Test
    void d950_theRowCarriesTheRouteCodeAndTheControlledFlag() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q4h");
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setRoute("INTRAVENOUS");
        rx.setControlledSubstance(true);

        List<NurseMedicationTaskResponseDTO> tasks = marFor(UUID.randomUUID(), hospitalId, rx, null);

        assertThat(tasks.get(0).getRouteCode()).isEqualTo("IV");
        assertThat(tasks.get(0).isControlledSubstance()).isTrue();
    }


    /* ── QA D1225 (round 24): the reason the administer call required is read back on the MAR row ── */

    @Test
    void D1225_theListCarriesTheEarlyDoseReasonAndTheHistory() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q4h");
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setCreatedAt(LocalDateTime.now().minusHours(9));
        MedicationAdministrationRecord held = closedDose(rx, rx.getCreatedAt(), MedicationAdministrationStatus.HELD);
        held.setReason("Patient au bloc");
        MedicationAdministrationRecord early = givenAt(rx, rx.getCreatedAt().plusHours(4), LocalDateTime.now().minusMinutes(1));
        early.setReason("Douleur intense, avis du médecin");
        early.setOverrideReason("Bracelet illisible");

        List<NurseMedicationTaskResponseDTO> tasks = marFor(UUID.randomUUID(), hospitalId, rx, null, early, held);

        NurseMedicationTaskResponseDTO row = tasks.get(0);
        assertThat(row.getLastOutcome()).isEqualTo("GIVEN");
        assertThat(row.isLastGivenEarly()).isTrue();
        assertThat(row.getLastReason()).isEqualTo("Douleur intense, avis du médecin");
        assertThat(row.getDoseHistory()).extracting(com.example.hms.payload.dto.nurse.MarDoseHistoryDTO::getStatus)
            .containsExactly("GIVEN", "HELD");
        assertThat(row.getDoseHistory().get(0).getOverrideReason()).isEqualTo("Bracelet illisible");
        assertThat(row.getDoseHistory().get(1).getReason()).isEqualTo("Patient au bloc");
        assertThat(row.getDoseHistory().get(1).isGivenEarly()).as("a held dose is not 'given early'").isFalse();
    }

    @Test
    void D1225_aDoseGivenOnTimeIsNotEarlyAndCarriesNoReason() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q24h");
        rx.setStatus(PrescriptionStatus.SIGNED);

        List<NurseMedicationTaskResponseDTO> tasks = marFor(UUID.randomUUID(), hospitalId, rx, null,
            closedDose(rx, rx.getCreatedAt(), MedicationAdministrationStatus.GIVEN));

        assertThat(tasks.get(0).isLastGivenEarly()).isFalse();
        assertThat(tasks.get(0).getLastReason()).isNull();
        assertThat(tasks.get(0).getDoseHistory()).hasSize(1);
    }

    @Test
    void D1225_theAdministerAnswerCarriesTheReasonItJustRecorded() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "TID");
        rx.setCreatedAt(LocalDateTime.now().minusHours(10));
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        MedicationAdministrationRecord previous = givenAt(rx, rx.getCreatedAt(), LocalDateTime.now().minusMinutes(2));
        java.util.List<MedicationAdministrationRecord> rows = new java.util.ArrayList<>(List.of(previous));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId())).thenAnswer(i -> List.copyOf(rows));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(i -> {
            MedicationAdministrationRecord saved = i.getArgument(0);
            rows.add(0, saved);
            return saved;
        });
        NurseMedicationAdministrationRequestDTO given = givenRequest();
        given.setEarlyDoseReason("Départ au bloc");

        NurseMedicationTaskResponseDTO answer =
            service.recordMedicationAdministration(rx.getId(), UUID.randomUUID(), hospitalId, given);

        assertThat(answer.getLastOutcome()).isEqualTo("GIVEN");
        assertThat(answer.isLastGivenEarly()).isTrue();
        assertThat(answer.getLastReason()).isEqualTo("Départ au bloc");
        assertThat(answer.getDoseHistory()).hasSize(2);
    }

    /* ════════════════════════════════════════════════════════════════════
       QA round 25: D1236 (the next dose counts from when the last one was
       really given) and D1237 (a frequency in words never ends the course)
       ════════════════════════════════════════════════════════════════════ */

    /**
     * QA D1236 replay (nsgive.js): morphine q6h, slots missed for two days, a
     * dose given a minute ago for a backlog slot. The row used to read
     * OVERDUE with the next backlog slot; the next dose is now due at the
     * minimum gap after the real dose and is not offered before it.
     */
    @Test
    void d1236_aLateDoseMovesTheNextDoseAndTheBacklogIsNotOfferedAgain() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q6h");
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setSignedAt(null);
        rx.setCreatedAt(LocalDateTime.now().minusDays(2));
        LocalDateTime given = LocalDateTime.now().minusMinutes(1);
        UUID nurseId = UUID.randomUUID();

        List<NurseMedicationTaskResponseDTO> tasks = marFor(nurseId, hospitalId, rx, null,
            givenAt(rx, rx.getCreatedAt(), given));

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getStatus()).isEqualTo("UPCOMING");
        assertThat(tasks.get(0).getDueTime()).isEqualTo(given.plusHours(5));
        assertThat(service.getMedicationTasks(nurseId, hospitalId, "OVERDUE")).isEmpty();
    }

    /** QA D1236: a dose given on time keeps the schedule exactly as before. */
    @Test
    void d1236_anOnTimeDoseKeepsTheSchedule() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "q6h");
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setSignedAt(null);
        LocalDateTime slot = rx.getCreatedAt();

        List<NurseMedicationTaskResponseDTO> tasks = marFor(UUID.randomUUID(), hospitalId, rx, null,
            givenAt(rx, slot, slot));

        assertThat(tasks.get(0).getDueTime()).isEqualTo(slot.plusHours(6));
        assertThat(tasks.get(0).getStatus()).isEqualTo("UPCOMING");
    }

    /**
     * QA D1237 replay (freqmar.js): the frequencies the prescriber wrote in
     * words. The course used to be COMPLETED after its first dose.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "toutes les six heures", "Toutes les 6 heures", "cada seis horas", "every six hours", "6h/6h"})
    void d1237_aFrequencyInWordsKeepsTheCourseAfterTheFirstDose(String frequency) {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, frequency);
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setSignedAt(null);
        LocalDateTime slot = rx.getCreatedAt();

        List<NurseMedicationTaskResponseDTO> tasks = marFor(UUID.randomUUID(), hospitalId, rx, null,
            givenAt(rx, slot, slot));

        assertThat(tasks.get(0).getStatus()).isEqualTo("UPCOMING");
        assertThat(tasks.get(0).getDueTime()).isEqualTo(slot.plusHours(6));
        assertThat(tasks.get(0).isFrequencyUnread()).isFalse();
    }

    /** QA D1237: "toutes les heures" is hourly; four hours after the first dose the course is overdue, not over. */
    @Test
    void d1237_hourlyInWordsIsHourly() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "toutes les heures");
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setSignedAt(null);
        LocalDateTime slot = rx.getCreatedAt();

        List<NurseMedicationTaskResponseDTO> tasks = marFor(UUID.randomUUID(), hospitalId, rx, null,
            givenAt(rx, slot, slot));

        assertThat(tasks.get(0).getStatus()).isEqualTo("OVERDUE");
        assertThat(tasks.get(0).getDueTime()).isEqualTo(slot.plusHours(1));
    }

    /**
     * QA D1237: a frequency the reader cannot place keeps the course open and
     * flagged; it is never read as a single dose.
     */
    @Test
    void d1237_anUnreadFrequencyKeepsTheCourseOpenAndFlagged() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "selon protocole");
        rx.setStatus(PrescriptionStatus.SIGNED);
        LocalDateTime slot = rx.getCreatedAt();

        List<NurseMedicationTaskResponseDTO> tasks = marFor(UUID.randomUUID(), hospitalId, rx, null,
            givenAt(rx, slot, slot));

        assertThat(tasks.get(0).getStatus()).isEqualTo("DUE");
        assertThat(tasks.get(0).isFrequencyUnread()).isTrue();
    }

    /**
     * QA D1508: an order asked about shows its flag from the start, as its prescription row does, before any
     * dose. QA D1491: an older order with no frequency is the same. The first dose keeps the order's own slot.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"selon protocole", "3 fois chaque jour", "20/j", "", "  "})
    void d1508_anUnreadOrderIsFlaggedBeforeItsFirstDose(String frequency) {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, frequency);
        rx.setStatus(PrescriptionStatus.SIGNED);

        List<NurseMedicationTaskResponseDTO> tasks = marFor(UUID.randomUUID(), hospitalId, rx, null);

        assertThat(tasks.get(0).isFrequencyUnread()).as(frequency).isTrue();
        assertThat(tasks.get(0).getDueTime()).as("its slot is the order's").isEqualTo(rx.getCreatedAt());
        // The flag changes no status: the same row as any schedule before its first dose.
        Prescription schedule = scheduledRx(hospitalId, "toutes les 6 heures");
        schedule.setStatus(PrescriptionStatus.SIGNED);
        schedule.setCreatedAt(rx.getCreatedAt());
        assertThat(tasks.get(0).getStatus()).as(frequency)
            .isEqualTo(marFor(UUID.randomUUID(), hospitalId, schedule, null).get(0).getStatus());
    }

    /** QA D1508: and only those. */
    @Test
    void d1508_aScheduleIsNotFlaggedBeforeItsFirstDose() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "toutes les 6 heures");
        rx.setStatus(PrescriptionStatus.SIGNED);
        assertThat(marFor(UUID.randomUUID(), hospitalId, rx, null).get(0).isFrequencyUnread()).isFalse();
    }

    /** QA D1237: the next dose of an unread order is accepted only with a reason, which is kept on the row. */
    @Test
    void d1237_anUnreadFrequencyAsksWhyEachLaterDoseIsGiven() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "selon protocole");
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(givenAt(rx, rx.getCreatedAt(), rx.getCreatedAt())));
        UUID rxId = rx.getId();
        UUID nurseId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, given))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", MarDoseInterval.FREQUENCY_UNREAD_KEY)
            .hasFieldOrPropertyWithValue("field", "earlyDoseReason");
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));

        ArgumentCaptor<MedicationAdministrationRecord> saved = ArgumentCaptor.forClass(MedicationAdministrationRecord.class);
        when(marRepository.save(saved.capture())).thenAnswer(i -> i.getArgument(0));
        given.setEarlyDoseReason("Prescriber confirmed: after each meal");
        service.recordMedicationAdministration(rxId, nurseId, hospitalId, given);
        assertThat(saved.getValue().getReason()).isEqualTo("Prescriber confirmed: after each meal");
    }

    /**
     * QA round 30 (user decision 2026-11-02): a PRN order is no longer ended
     * after its first dose. A minute after a dose it is still offered (DUE),
     * flagged early, with the prescriber's interval on the row; it never
     * reads OVERDUE.
     */
    @Test
    void prn_staysOfferedAfterADoseFlaggedEarlyWithTheInterval() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "si besoin");
        rx.setAsNeeded(true);
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setPrnMinIntervalHours(6);
        LocalDateTime given = LocalDateTime.now().minusMinutes(1);
        UUID nurseId = UUID.randomUUID();

        List<NurseMedicationTaskResponseDTO> tasks = marFor(nurseId, hospitalId, rx, null,
            givenAt(rx, given, given));

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getStatus()).isEqualTo("DUE");
        assertThat(tasks.get(0).isAsNeeded()).isTrue();
        assertThat(tasks.get(0).isEarlyDose()).isTrue();
        assertThat(tasks.get(0).getPrnMinIntervalHours()).isEqualTo(6);
        assertThat(tasks.get(0).getPrnLabel()).isNotBlank();
        assertThat(service.getMedicationTasks(nurseId, hospitalId, "OVERDUE")).isEmpty();
    }

    /**
     * QA D1375 replay (prn31.log give1): the answer to an early PRN dose said
     * asNeeded=false, no interval, no label, earlyDose=false, while the next
     * GET of the MAR said otherwise. Guard: every field of the answer equals
     * the list row read right after, except the fields of the dose itself.
     * A new field on the row that an administer answer does not carry fails here.
     */
    @Test
    void d1375_theAdministerAnswerOfAPrnOrderSaysWhatTheListSays() throws Exception {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "au besoin");
        rx.setAsNeeded(true);
        rx.setStatus(PrescriptionStatus.SIGNED);
        rx.setPrnMinIntervalHours(6);
        LocalDateTime earlier = LocalDateTime.now().minusHours(1);
        List<MedicationAdministrationRecord> rows = new java.util.ArrayList<>(List.of(givenAt(rx, earlier, earlier)));
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        lenient().when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenAnswer(i -> List.copyOf(rows));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(i -> {
            MedicationAdministrationRecord saved = i.getArgument(0);
            saved.setId(UUID.randomUUID());
            rows.add(0, saved);
            return saved;
        });
        UUID nurseId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO dose = givenRequest();
        dose.setEarlyDoseReason("Douleur 8/10, médecin prévenu");

        NurseMedicationTaskResponseDTO answer = service.recordMedicationAdministration(rx.getId(), nurseId,
            hospitalId, dose);

        assertThat(answer.getStatus()).isEqualTo("GIVEN");
        assertThat(answer.isAsNeeded()).isTrue();
        assertThat(answer.getPrnMinIntervalHours()).isEqualTo(6);
        assertThat(answer.getPrnLabel()).isNotBlank();
        assertThat(answer.isEarlyDose()).isTrue();

        UUID patientId = rx.getPatient().getId();
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, rx.getPatient().getFullName(), "Awa", "Kabore")));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx));
        NurseMedicationTaskResponseDTO row = service.getMedicationTasks(nurseId, hospitalId, null).get(0);

        // The dose itself: its own row id, the outcome recorded, its slot, its witness.
        java.util.Set<String> ofTheDose = java.util.Set.of("id", "status", "dueTime", "witnessName");
        List<String> differ = new java.util.ArrayList<>();
        int compared = 0;
        for (java.lang.reflect.Field field : NurseMedicationTaskResponseDTO.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || ofTheDose.contains(field.getName())) {
                continue;
            }
            field.setAccessible(true);
            compared++;
            if (!java.util.Objects.equals(field.get(answer), field.get(row))) {
                differ.add(field.getName() + ": answer=" + field.get(answer) + " list=" + field.get(row));
            }
        }
        assertThat(compared).isGreaterThanOrEqualTo(20);
        assertThat(differ).isEmpty();
    }

    /**
     * QA D1375 guard (source scan): a MAR row is built in ONE shape. Every
     * NurseMedicationTaskResponseDTO built in main code goes through
     * withOrderReading (the list) or answerAfterDose (every administer
     * answer), or is a whole-row copy (toBuilder), and only
     * NurseTaskServiceImpl builds one.
     */
    @Test
    void d1375_everyMarRowIsBuiltThroughTheOrderReading() throws Exception {
        java.nio.file.Path main = java.nio.file.Path.of("src", "main", "java");
        List<String> builders;
        try (var files = java.nio.file.Files.walk(main)) {
            builders = files.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> {
                    try {
                        return java.nio.file.Files.readString(f).contains("NurseMedicationTaskResponseDTO.builder()");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .map(f -> f.getFileName().toString())
                .toList();
        }
        assertThat(builders).containsExactly("NurseTaskServiceImpl.java");
        String source = java.nio.file.Files.readString(
            main.resolve("com/example/hms/service/impl/NurseTaskServiceImpl.java"));
        List<String> unwrapped = new java.util.ArrayList<>();
        int found = 0;
        for (int at = source.indexOf("NurseMedicationTaskResponseDTO.builder()"); at >= 0;
             at = source.indexOf("NurseMedicationTaskResponseDTO.builder()", at + 1)) {
            found++;
            String before = source.substring(Math.max(0, at - 60), at);
            if (!before.contains("withOrderReading(") && !before.contains("answerAfterDose(")) {
                unwrapped.add(before.trim());
            }
        }
        assertThat(found).isGreaterThanOrEqualTo(4);
        assertThat(unwrapped).isEmpty();
    }

    /** QA round 30: a second PRN dose inside the interval is accepted only with a reason, kept on the row. */
    @Test
    void prn_aSecondDoseInsideTheIntervalAsksWhy() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "PRN");
        rx.setAsNeeded(true);
        rx.setPrnMinIntervalHours(4);
        LocalDateTime given = LocalDateTime.now().minusHours(1);
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId()))
            .thenReturn(List.of(givenAt(rx, given, given)));
        UUID rxId = rx.getId();
        UUID nurseId = UUID.randomUUID();
        NurseMedicationAdministrationRequestDTO dose = givenRequest();

        assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, dose))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", MarDoseInterval.TOO_SOON_KEY)
            .hasFieldOrPropertyWithValue("field", "earlyDoseReason");
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));

        ArgumentCaptor<MedicationAdministrationRecord> saved = ArgumentCaptor.forClass(MedicationAdministrationRecord.class);
        when(marRepository.save(saved.capture())).thenAnswer(i -> i.getArgument(0));
        dose.setEarlyDoseReason("Douleur 8/10, médecin prévenu");
        service.recordMedicationAdministration(rxId, nurseId, hospitalId, dose);
        assertThat(saved.getValue().getReason()).isEqualTo("Douleur 8/10, médecin prévenu");
        // The dose fills a slot of now (the TIME right reads it), never the end of the interval.
        assertThat(saved.getValue().getScheduledTime()).isAfter(given.plusMinutes(30));
        assertThat(saved.getValue().getScheduledTime()).isBefore(given.plusHours(2));
    }

    /**
     * QA D1255 (round 26): the MAR list was cut at 20 rows for the whole
     * hospital, before any sort, so a controlled morphine order with a dose
     * already given vanished. Every active order of the scope is listed,
     * soonest due first, a finished course last.
     */
    @Test
    void d1255_everyActiveOrderIsListedSoonestFirst() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        List<PatientResponseDTO> patients = new java.util.ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusHours(30);
        for (int i = 0; i < 25; i++) {
            Prescription rx = scheduledRx(hospitalId, "q12h");
            rx.setStatus(PrescriptionStatus.SIGNED);
            rx.setMedicationName("Order " + i);
            // Later patients' orders start earlier, so the read order is not the due order.
            rx.setCreatedAt(base.plusMinutes(60L * (25 - i)));
            UUID patientId = rx.getPatient().getId();
            patients.add(patient(patientId, "Patient " + i, "P", String.valueOf(i)));
            when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(rx));
        }
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(patients);

        List<NurseMedicationTaskResponseDTO> tasks = service.getMedicationTasks(nurseId, hospitalId, null);

        assertThat(tasks).hasSize(25);
        assertThat(tasks).extracting(NurseMedicationTaskResponseDTO::getDueTime)
            .isSortedAccordingTo(java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
        assertThat(tasks.get(0).getMedication()).isEqualTo("Order 24");
    }

    /**
     * QA D1254 (round 26, regression of D1236): hourly morphine given on time
     * five minutes ago. The next dose is inside its slot window but before the
     * 30-minute gap: DUE (so Give is offered and the server's reason refusal
     * opens the early-dose dialog), flagged earlyDose. Round 25 made it
     * UPCOMING and the early-dose path unreachable.
     */
    @Test
    void d1254_aDoseInsideItsSlotWindowButBeforeTheGapIsDueAndFlaggedEarly() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "toutes les heures");
        rx.setStatus(PrescriptionStatus.SIGNED);
        LocalDateTime slot = LocalDateTime.now().minusMinutes(5);
        rx.setSignedAt(slot);
        UUID patientId = rx.getPatient().getId();
        MedicationAdministrationRecord given = closedDose(rx, slot, MedicationAdministrationStatus.GIVEN);
        given.setAdministeredAt(slot);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Rihanata Sankara", "Rihanata", "Sankara")));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId())).thenReturn(List.of(given));

        List<NurseMedicationTaskResponseDTO> pending = service.getMedicationTasks(nurseId, hospitalId, "PENDING");

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getStatus()).isEqualTo("DUE");
        assertThat(pending.get(0).isEarlyDose()).isTrue();
    }

    /** QA D1254: once the gap has passed, the same order is DUE and not early. */
    @Test
    void d1254_afterTheGapTheDoseIsNoLongerEarly() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "toutes les heures");
        rx.setStatus(PrescriptionStatus.SIGNED);
        LocalDateTime slot = LocalDateTime.now().minusMinutes(40);
        rx.setSignedAt(slot);
        UUID patientId = rx.getPatient().getId();
        MedicationAdministrationRecord given = closedDose(rx, slot, MedicationAdministrationStatus.GIVEN);
        given.setAdministeredAt(slot);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Rihanata Sankara", "Rihanata", "Sankara")));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId())).thenReturn(List.of(given));

        NurseMedicationTaskResponseDTO row = service.getMedicationTasks(nurseId, hospitalId, null).get(0);

        assertThat(row.getStatus()).isEqualTo("DUE");
        assertThat(row.isEarlyDose()).isFalse();
    }

    /**
     * QA D1262 (round 26): a REFUSED outcome recorded late on a missed backlog
     * slot. The row moves to the schedule's first slot after the refusal; it
     * is not OVERDUE on the next missed slot.
     */
    @Test
    void d1262_aLateRefusalOnABacklogSlotIsNotOverdue() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "trois fois par jour");
        rx.setStatus(PrescriptionStatus.SIGNED);
        LocalDateTime missed = LocalDateTime.now().minusHours(20);
        rx.setSignedAt(missed);
        UUID patientId = rx.getPatient().getId();
        MedicationAdministrationRecord refused = closedDose(rx, missed, MedicationAdministrationStatus.REFUSED);
        refused.setAdministeredAt(LocalDateTime.now().minusMinutes(1));
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Aminatou Diallo", "Aminatou", "Diallo")));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx));
        when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId())).thenReturn(List.of(refused));

        NurseMedicationTaskResponseDTO row = service.getMedicationTasks(nurseId, hospitalId, null).get(0);

        assertThat(row.getStatus()).isEqualTo("UPCOMING");
        assertThat(row.getDueTime()).isEqualTo(missed.plusHours(24));
    }

    /** QA D1255 class: an uncapped pending-handoff list reads every pending handoff, unpaged, and keeps them all. */
    @Test
    void d1255_anUncappedHandoffListReadsAndKeepsEveryPendingHandoff() {
        UUID hospitalId = UUID.randomUUID();
        Patient p = mock(Patient.class);
        lenient().when(p.getId()).thenReturn(UUID.randomUUID());
        List<NurseHandoff> many = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(NurseHandoff.builder().patient(p).direction("SHIFT_CHANGE").build());
        }
        when(nurseHandoffRepository.findByHospital_IdAndStatusOrderByCreatedAtDesc(
            hospitalId, "PENDING", Pageable.unpaged())).thenReturn(many);

        assertThat(service.getHandoffSummaries(null, null, hospitalId,
            com.example.hms.service.NurseTaskService.NO_LIMIT, null)).hasSize(30);
    }
    /* ── QA round 33: D1392 (an order's own status), D1402 (tile == list) ── */

    private static org.assertj.core.api.ThrowingConsumer<Throwable> refusedAs(String key) {
        return ex -> assertThat(ex).isInstanceOf(com.example.hms.exception.ConflictException.class).hasMessage(key);
    }

    private MarVerificationRequestDTO scans() {
        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue("p");
        req.setMedicationScanValue("m");
        req.setDoseScanValue("d");
        req.setRouteScanValue("IV");
        return req;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PrescriptionStatus.class, names = {"CANCELLED", "DISCONTINUED"})
    void d1392_aWithdrawnOrderTakesNoDoseAndNoBedsideCheck(PrescriptionStatus withdrawn) {
        UUID hospitalId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        Prescription rx = highRiskRx(hospitalId, true);
        rx.setStatus(withdrawn);
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        lenient().when(prescriptionRepository.findByIdAndHospitalIdForUpdate(rx.getId(), hospitalId))
            .thenReturn(Optional.of(rx));
        NurseMedicationAdministrationRequestDTO given = givenRequest();
        MarVerificationRequestDTO scans = scans();

        assertThatThrownBy(() -> service.recordMedicationAdministration(rx.getId(), nurseId, hospitalId, given))
            .satisfies(refusedAs("nurse.mar.orderWithdrawn"));
        assertThatThrownBy(() -> service.verifyMedicationAdministration(rx.getId(), nurseId, hospitalId, scans))
            .satisfies(refusedAs("nurse.mar.orderWithdrawn"));
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }

    /** The panel opened before the cancel: its PENDING row already exists, so the MAR-row path is the one taken. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PrescriptionStatus.class, names = {"CANCELLED", "DISCONTINUED"})
    void d1392_aPanelOpenedBeforeTheWithdrawalIsRefusedOnItsPendingRow(PrescriptionStatus withdrawn) {
        UUID hospitalId = UUID.randomUUID();
        UUID nurseId = UUID.randomUUID();
        Prescription rx = highRiskRx(hospitalId, true);
        rx.setStatus(withdrawn);
        MedicationAdministrationRecord pending = closedDose(rx, LocalDateTime.now(), MedicationAdministrationStatus.PENDING);
        when(marRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        NurseMedicationAdministrationRequestDTO given = givenRequest();
        MarVerificationRequestDTO scans = scans();

        assertThatThrownBy(() -> service.recordMedicationAdministration(pending.getId(), nurseId, hospitalId, given))
            .satisfies(refusedAs("nurse.mar.orderWithdrawn"));
        assertThatThrownBy(() -> service.verifyMedicationAdministration(pending.getId(), nurseId, hospitalId, scans))
            .satisfies(refusedAs("nurse.mar.orderWithdrawn"));
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
        assertThat(pending.getStatus()).as("no dose recorded").isEqualTo(MedicationAdministrationStatus.PENDING);
    }

    /** The order's lock is taken before its status is read, or a cancel committed meanwhile is missed. */
    @Test
    void d1392_theOrderIsLockedBeforeItsStatusIsRead() {
        UUID hospitalId = UUID.randomUUID();
        UUID rxId = UUID.randomUUID();
        Prescription rx = mock(Prescription.class);
        when(rx.getId()).thenReturn(rxId);
        when(rx.getStatus()).thenReturn(PrescriptionStatus.CANCELLED);
        MedicationAdministrationRecord pending = MedicationAdministrationRecord.builder()
            .prescription(rx).status(MedicationAdministrationStatus.PENDING).build();
        pending.setId(UUID.randomUUID());
        when(marRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        assertThatThrownBy(() -> service.recordMedicationAdministration(pending.getId(), UUID.randomUUID(), hospitalId, given))
            .satisfies(refusedAs("nurse.mar.orderWithdrawn"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(prescriptionRepository, rx);
        order.verify(prescriptionRepository).findByIdAndHospitalIdForUpdate(rxId, hospitalId);
        order.verify(rx).getStatus();
    }

    /** What must not break: the refusal a discharged stay's order has always had keeps its wording. */
    @Test
    void d1392_aDischargedStaysOrderKeepsTheDischargeRefusal() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = highRiskRx(hospitalId, true);
        rx.setStatus(PrescriptionStatus.DISCONTINUED);
        rx.setCreatedAt(LocalDateTime.now().minusHours(3));
        Admission stay = new Admission();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionDateTime(LocalDateTime.now().minusHours(5));
        stay.setActualDischargeDateTime(LocalDateTime.now().minusHours(1));
        when(prescriptionRepository.findByIdAndHospitalIdForUpdate(rx.getId(), hospitalId)).thenReturn(Optional.of(rx));
        when(admissionRepository.findByPatient_IdAndHospital_Id(rx.getPatient().getId(), hospitalId))
            .thenReturn(List.of(stay));
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        assertThatThrownBy(() -> service.recordMedicationAdministration(rx.getId(), UUID.randomUUID(), hospitalId, given))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "nurse.mar.patientDischarged");
    }

    /** QA D1393: a live order on a cancelled stay (a prepared fill holds it) is refused too; no discharge exists to say so. */
    @Test
    void d1393_aLiveOrderOfACancelledStayIsRefused() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = highRiskRx(hospitalId, true);
        rx.setStatus(PrescriptionStatus.SIGNED);
        Encounter cancelledStay = new Encounter();
        cancelledStay.setAdmissionId(UUID.randomUUID());
        cancelledStay.setStatus(com.example.hms.enums.EncounterStatus.CANCELLED);
        rx.setEncounter(cancelledStay);
        when(prescriptionRepository.findByIdAndHospitalIdForUpdate(rx.getId(), hospitalId)).thenReturn(Optional.of(rx));
        NurseMedicationAdministrationRequestDTO given = givenRequest();

        assertThatThrownBy(() -> service.recordMedicationAdministration(rx.getId(), UUID.randomUUID(), hospitalId, given))
            .satisfies(refusedAs("nurse.mar.stayEnded"));
    }

    /** What must not break: the supply statuses say where the medicine is; the panel opened before the hand-over still records. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PrescriptionStatus.class,
        names = {"DISPENSED", "PARTIALLY_FILLED", "PENDING_STOCK", "PENDING_CLARIFICATION", "SIGNED", "TRANSMITTED"})
    void d1392_anOrderThePrescriberStillStandsBehindTakesItsDose(PrescriptionStatus supply) {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = highRiskRx(hospitalId, true);
        rx.setControlledSubstance(false);
        rx.setMedicationName("Paracetamol");
        rx.setStatus(supply);
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        marSaveEchoes();

        assertThatCode(() -> service.recordMedicationAdministration(rx.getId(), UUID.randomUUID(), null, givenRequest()))
            .doesNotThrowAnyException();
    }

    private PatientVitalSign highNews2Set(LocalDateTime at) {
        // The D207 set: T 38.9, HR 112, RR 26, BP 95/60, SpO2 91 -> NEWS2 HIGH.
        return PatientVitalSign.builder().recordedAt(at)
            .temperatureCelsius(38.9).heartRateBpm(112).respiratoryRateBpm(26)
            .systolicBpMmHg(95).diastolicBpMmHg(60).spo2Percent(91).build();
    }

    /**
     * QA D1402: tile 30, list 34. A patient on a shortened NEWS2 interval whose
     * last set is under four hours old is due in the list and was absent from
     * the tile. Every shape of due round, one count.
     */
    @Test
    void d1402_theVitalsTileIsTheRowCountOfItsList() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID neverMeasured = UUID.randomUUID();
        UUID shortened = UUID.randomUUID();
        UUID longAgo = UUID.randomUUID();
        UUID justMeasured = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2026, 11, 6, 17, 43);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of(
            patient(neverMeasured, "A A", "A", "A"), patient(shortened, "B B", "B", "B"),
            patient(longAgo, "C C", "C", "C"), patient(justMeasured, "D D", "D", "D")));
        when(vitalSignRepository.findLatestObservationSet(neverMeasured, hospitalId)).thenReturn(Optional.empty());
        when(vitalSignRepository.findLatestObservationSet(shortened, hospitalId))
            .thenReturn(Optional.of(highNews2Set(fixedNow.minusMinutes(20))));
        when(vitalSignRepository.findLatestObservationSet(longAgo, hospitalId))
            .thenReturn(Optional.of(PatientVitalSign.builder().recordedAt(fixedNow.minusHours(5)).build()));
        when(vitalSignRepository.findLatestObservationSet(justMeasured, hospitalId))
            .thenReturn(Optional.of(PatientVitalSign.builder().recordedAt(fixedNow).build()));

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);
            List<NurseVitalTaskResponseDTO> list = service.getDueVitals(nurseId, hospitalId, null);
            NurseDashboardSummaryDTO summary = service.getDashboardSummary(nurseId, hospitalId);

            assertThat(list).extracting(NurseVitalTaskResponseDTO::getPatientId)
                .containsExactlyInAnyOrder(neverMeasured, shortened, longAgo);
            assertThat(summary.getVitalsDue()).isEqualTo(list.size());
        }
    }

    /**
     * User decision (round 33): routine rounds fall due every 2 hours, and the look-ahead is
     * 30 minutes. Both used one constant, so a patient measured 10 minutes ago was already "due".
     */
    @Test
    void routineVitals_areDueInTheLastThirtyMinutesOfTheirTwoHours_inTheListAndTheTile() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID justMeasured = UUID.randomUUID();
        UUID dueInAnHour = UUID.randomUUID();
        UUID dueIn31Minutes = UUID.randomUUID();
        UUID dueIn29Minutes = UUID.randomUUID();
        UUID overdue = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2026, 11, 6, 17, 43);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of(
            patient(justMeasured, "A A", "A", "A"), patient(dueInAnHour, "B B", "B", "B"),
            patient(dueIn31Minutes, "C C", "C", "C"), patient(dueIn29Minutes, "D D", "D", "D"),
            patient(overdue, "E E", "E", "E")));
        stubLastSet(justMeasured, hospitalId, fixedNow.minusMinutes(10));
        stubLastSet(dueInAnHour, hospitalId, fixedNow.minusHours(1));
        stubLastSet(dueIn31Minutes, hospitalId, fixedNow.minusMinutes(89));
        stubLastSet(dueIn29Minutes, hospitalId, fixedNow.minusMinutes(91));
        stubLastSet(overdue, hospitalId, fixedNow.minusMinutes(121));

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);
            List<NurseVitalTaskResponseDTO> list = service.getDueVitals(nurseId, hospitalId, null);
            NurseDashboardSummaryDTO summary = service.getDashboardSummary(nurseId, hospitalId);

            assertThat(list).extracting(NurseVitalTaskResponseDTO::getPatientId)
                .containsExactly(overdue, dueIn29Minutes);
            assertThat(list.get(0).isOverdue()).isTrue();
            assertThat(list.get(1).isOverdue()).isFalse();
            assertThat(list.get(1).getDueTime()).isEqualTo(fixedNow.plusMinutes(29));
            assertThat(summary.getVitalsDue()).isEqualTo(list.size());
        }
    }

    /** The reader's look-ahead shows more rounds; it never moves when a round falls due. */
    @Test
    void routineVitals_aWiderLookAheadNeverStretchesTheTwoHourInterval() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID fiveHoursAgo = UUID.randomUUID();
        UUID oneHourAgo = UUID.randomUUID();
        LocalDateTime fixedNow = LocalDateTime.of(2026, 11, 6, 17, 43);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null)).thenReturn(List.of(
            patient(fiveHoursAgo, "A A", "A", "A"), patient(oneHourAgo, "B B", "B", "B")));
        stubLastSet(fiveHoursAgo, hospitalId, fixedNow.minusHours(5));
        stubLastSet(oneHourAgo, hospitalId, fixedNow.minusHours(1));

        try (MockedStatic<LocalDateTime> mockedNow = mockStatic(LocalDateTime.class)) {
            mockedNow.when(LocalDateTime::now).thenReturn(fixedNow);
            List<NurseVitalTaskResponseDTO> list = service.getDueVitals(nurseId, hospitalId, Duration.ofHours(8));

            assertThat(list).extracting(NurseVitalTaskResponseDTO::getPatientId)
                .containsExactly(fiveHoursAgo, oneHourAgo);
            assertThat(list.get(0).isOverdue()).isTrue();
            assertThat(list.get(0).getDueTime()).isEqualTo(fixedNow.minusHours(3));
            assertThat(list.get(1).getDueTime()).isEqualTo(fixedNow.plusHours(1));
        }
    }

    private void stubLastSet(UUID patientId, UUID hospitalId, LocalDateTime recordedAt) {
        when(vitalSignRepository.findLatestObservationSet(patientId, hospitalId))
            .thenReturn(Optional.of(PatientVitalSign.builder().recordedAt(recordedAt).build()));
    }

    /**
     * QA D1402: "Médicaments à administrer" read 9 against 21 rows, and the two
     * labels read the same. Each medication tile is a named filter of the rows
     * the eMAR lists, as-needed orders counted apart.
     */
    @Test
    void d1402_theMedicationTilesAreFiltersOfTheEmarRows() {
        UUID nurseId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        Prescription due = scheduledRx(hospitalId, "q12h");
        due.setStatus(PrescriptionStatus.SIGNED);
        due.setCreatedAt(now.minusMinutes(10));
        UUID patientId = due.getPatient().getId();
        Prescription overdue = scheduledRx(hospitalId, "q12h");
        overdue.setPatient(due.getPatient());
        overdue.setStatus(PrescriptionStatus.SIGNED);
        overdue.setCreatedAt(now.minusHours(3));
        Prescription upcoming = scheduledRx(hospitalId, "q12h");
        upcoming.setPatient(due.getPatient());
        upcoming.setStatus(PrescriptionStatus.SIGNED);
        upcoming.setCreatedAt(now.minusHours(13));
        lenient().when(marRepository.findByPrescription_IdOrderByScheduledTimeDesc(upcoming.getId()))
            .thenReturn(List.of(closedDose(upcoming, upcoming.getCreatedAt().plusHours(12), MedicationAdministrationStatus.GIVEN)));
        Prescription asNeeded = scheduledRx(hospitalId, "si besoin");
        asNeeded.setPatient(due.getPatient());
        asNeeded.setStatus(PrescriptionStatus.SIGNED);
        asNeeded.setAsNeeded(true);
        asNeeded.setPrnMinIntervalHours(6);
        Prescription withdrawn = scheduledRx(hospitalId, "q12h");
        withdrawn.setPatient(due.getPatient());
        withdrawn.setStatus(PrescriptionStatus.CANCELLED);
        when(nurseDashboardService.getPatientsForNurse(nurseId, hospitalId, null))
            .thenReturn(List.of(patient(patientId, "Awa Kabore", "Awa", "Kabore")));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(due, overdue, upcoming, asNeeded, withdrawn));

        List<NurseMedicationTaskResponseDTO> rows = service.getMedicationTasks(nurseId, hospitalId, null);
        NurseDashboardSummaryDTO summary = service.getDashboardSummary(nurseId, hospitalId);

        assertThat(rows).as("the withdrawn order is on no list").hasSize(4);
        assertThat(summary.getMedicationsDue()).isEqualTo(
            rows.stream().filter(r -> "DUE".equals(r.getStatus()) && !r.isAsNeeded()).count());
        assertThat(summary.getMedicationsOverdue()).isEqualTo(
            rows.stream().filter(r -> "OVERDUE".equals(r.getStatus())).count());
        assertThat(summary.getMedicationsAsNeeded()).isEqualTo(1L)
            .isEqualTo(rows.stream().filter(NurseMedicationTaskResponseDTO::isAsNeeded).count());
    }

    /** QA D1402: the workboard card reads the eMAR's rule: another visit's take-home order is no ward dose. */
    @Test
    void d1402_theWorkboardCardCountsTheRowsTheEmarLists() {
        UUID hospitalId = UUID.randomUUID();
        Prescription ofTheStay = scheduledRx(hospitalId, "q12h");
        ofTheStay.setStatus(PrescriptionStatus.SIGNED);
        ofTheStay.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        UUID patientId = ofTheStay.getPatient().getId();
        Encounter consultation = new Encounter();
        consultation.setEncounterType(com.example.hms.enums.EncounterType.CONSULTATION);
        consultation.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
        Prescription ofAVisit = scheduledRx(hospitalId, "q12h");
        ofAVisit.setPatient(ofTheStay.getPatient());
        ofAVisit.setStatus(PrescriptionStatus.SIGNED);
        ofAVisit.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        ofAVisit.setEncounter(consultation);
        Admission adm = new Admission();
        adm.setId(UUID.randomUUID());
        adm.setPatient(ofTheStay.getPatient());
        adm.setStatus(AdmissionStatus.ACTIVE);
        adm.setAdmissionDateTime(LocalDateTime.now().minusHours(5));
        when(admissionRepository.findActiveAdmissionsByHospital(hospitalId)).thenReturn(List.of(adm));
        when(vitalSignRepository.findLatestObservationSet(patientId, hospitalId))
            .thenReturn(Optional.of(highNews2Set(LocalDateTime.now().minusMinutes(20))));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(ofTheStay, ofAVisit));

        List<NurseWorkboardPatientDTO> cards = service.getWorkboard(UUID.randomUUID(), hospitalId);

        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.getMedsDue()).isEqualTo(1L);
            assertThat(card.isVitalsDue()).as("overdue on its NEWS2 interval, as in the list").isTrue();
        });
    }

    /** QA D1428 class: a recorded dose keeps its scans, its override and its nurse. */
    @Test
    void qaD1428_aRecordedDoseIsNotVerifiedAgain() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        MedicationAdministrationRecord mar = seededMar(patientId, hospitalId, LocalDateTime.now());
        mar.setStatus(com.example.hms.enums.MedicationAdministrationStatus.GIVEN);
        mar.setPatientScanValue("as scanned");
        when(marRepository.findById(marId)).thenReturn(Optional.of(mar));
        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue("rewritten");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.verifyMedicationAdministration(marId, null, hospitalId, req))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .extracting("messageKey").isEqualTo("nurse.mar.alreadyRecorded");
        assertThat(mar.getPatientScanValue()).isEqualTo("as scanned");
    }

    /* ── CR-03: the documented paracetamol exposure at the dose ────────────────────── */

    private static com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard cr03_exposureCard() {
        return new com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard("3500 mg recorded as given", "detail",
            com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard.Indicator.WARNING,
            new com.example.hms.cdshooks.dto.CdsHookDtos.Source("Adult paracetamol", null, null),
            null, null, null, UUID.randomUUID().toString());
    }

    private MedicationAdministrationRecord cr03_verifiedDose(UUID marId, UUID patientId, UUID hospitalId) {
        MedicationAdministrationRecord mar = seededMar(patientId, hospitalId, LocalDateTime.now());
        mar.getPrescription().setId(UUID.randomUUID());
        mar.setPatientScanValue(patientId.toString());
        mar.setMedicationScanValue("AMOX-500");
        mar.setDoseScanValue("500 mg");
        mar.setRouteScanValue("PO");
        mar.setFiveRightsStatus(FiveRightsStatus.VERIFIED);
        mar.setScanVerifiedAt(LocalDateTime.now());
        when(prescriptionRepository.findByIdAndHospitalIdForUpdate(mar.getPrescription().getId(), hospitalId))
            .thenReturn(Optional.of(mar.getPrescription()));
        when(marRepository.findById(marId)).thenReturn(Optional.of(mar));
        when(marRepository.save(mar)).thenReturn(mar);
        return mar;
    }

    @Test
    void cr03_theBedsideVerificationCarriesTheExposureAndNoRefusal() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        MedicationAdministrationRecord mar = seededMar(patientId, hospitalId, LocalDateTime.now());
        mar.getPrescription().setId(UUID.randomUUID());
        when(marRepository.findById(marId)).thenReturn(Optional.of(mar));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        com.example.hms.service.emar.ParacetamolExposureCheck check = mock(com.example.hms.service.emar.ParacetamolExposureCheck.class);
        service.setExposureCheck(check);
        com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard card = cr03_exposureCard();
        when(check.cards(mar.getPrescription().getId(), hospitalId, false)).thenReturn(List.of(card));
        MarVerificationRequestDTO req = new MarVerificationRequestDTO();
        req.setPatientScanValue(patientId.toString());
        req.setMedicationScanValue("AMOX-500");
        req.setDoseScanValue("500 mg");
        req.setRouteScanValue("PO");

        MarVerificationResponseDTO resp = service.verifyMedicationAdministration(marId, null, hospitalId, req);

        assertThat(resp.getCdsAdvisories()).contains(card);
        assertThat(resp.isAllPassed()).isTrue();
    }

    @Test
    void cr03_aRecordedDoseCarriesTheExposureAndHeldOrRefusedDoesNotReadIt() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        MedicationAdministrationRecord mar = cr03_verifiedDose(marId, patientId, hospitalId);
        com.example.hms.service.emar.ParacetamolExposureCheck check = mock(com.example.hms.service.emar.ParacetamolExposureCheck.class);
        service.setExposureCheck(check);
        com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard card = cr03_exposureCard();
        when(check.cards(mar.getPrescription().getId(), hospitalId, true)).thenReturn(List.of(card));
        NurseMedicationAdministrationRequestDTO req = new NurseMedicationAdministrationRequestDTO();
        req.setStatus("GIVEN");

        NurseMedicationTaskResponseDTO answer = service.recordMedicationAdministration(marId, null, hospitalId, req);

        assertThat(mar.getStatus()).isEqualTo(MedicationAdministrationStatus.GIVEN);
        assertThat(answer.getCdsAdvisories()).contains(card);
    }

    /** A failed read says the exposure is unknown; the dose stands. The real transaction: ParacetamolExposureAfterCommitIT. */
    @Test
    void cr03_aFailedReadSaysUnknownAndTheDoseStands() {
        UUID marId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        MedicationAdministrationRecord mar = cr03_verifiedDose(marId, patientId, hospitalId);
        com.example.hms.service.emar.ParacetamolExposureCheck check = mock(com.example.hms.service.emar.ParacetamolExposureCheck.class);
        service.setExposureCheck(check);
        com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard unknown = cr03_exposureCard();
        when(check.cards(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenThrow(new IllegalStateException("boom"));
        when(check.unknownCard(any())).thenReturn(unknown);
        NurseMedicationAdministrationRequestDTO req = new NurseMedicationAdministrationRequestDTO();
        req.setStatus("GIVEN");

        NurseMedicationTaskResponseDTO answer = service.recordMedicationAdministration(marId, null, hospitalId, req);

        assertThat(mar.getStatus()).isEqualTo(MedicationAdministrationStatus.GIVEN);
        assertThat(answer.getCdsAdvisories()).contains(unknown);
    }

    /** F6, provisional and for the clinical lead: a held order gives no new dose; a withheld or refused one is documented. */
    @Test
    void f6_aHeldOrderTakesAWithheldOrRefusedDoseAndNoNewOne() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "toutes les 6 heures");
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(call -> call.getArgument(0));
        com.example.hms.service.support.OrderReviewHoldGuard.useHolds((type, id) -> id.equals(rx.getId()));
        try {
            UUID rxId = rx.getId();
            UUID nurseId = UUID.randomUUID();
            NurseMedicationAdministrationRequestDTO given = givenRequest();
            assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, given))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .hasFieldOrPropertyWithValue("field", "givenBeforeHoldAt");
            verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));

            for (String status : List.of("HELD", "REFUSED")) {
                NurseMedicationAdministrationRequestDTO withheld = new NurseMedicationAdministrationRequestDTO();
                withheld.setStatus(status);
                withheld.setNote("Patient asleep");
                assertThatCode(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, withheld)).as(status)
                    .doesNotThrowAnyException();
            }
        } finally {
            com.example.hms.service.support.OrderReviewHoldGuard.useHolds(null);
        }
    }

    /** F6: a dose given before the hold is chartable, at its own time, with a reason, or a double dose becomes possible. */
    @Test
    void f6_aDoseGivenBeforeTheHoldIsChartedAtItsTimeAndAudited() {
        UUID hospitalId = UUID.randomUUID();
        Prescription rx = scheduledRx(hospitalId, "toutes les 6 heures");
        LocalDateTime held = LocalDateTime.now().minusMinutes(30);
        rx.setCreatedAt(held.minusDays(1));
        when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
        com.example.hms.service.OrderReviewHoldService review = mock(com.example.hms.service.OrderReviewHoldService.class);
        when(review.pendingSince(rx)).thenReturn(held);
        when(marRepository.save(any(MedicationAdministrationRecord.class))).thenAnswer(call -> call.getArgument(0));
        com.example.hms.service.support.OrderReviewHoldGuard.useReview(review);
        try {
            UUID rxId = rx.getId();
            UUID nurseId = UUID.randomUUID();
            NurseMedicationAdministrationRequestDTO noReason = givenRequest();
            noReason.setGivenBeforeHoldAt(held.minusHours(1));
            assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, noReason))
                .hasFieldOrPropertyWithValue("field", "givenBeforeHoldReason");
            for (LocalDateTime wrong : List.of(held, held.plusMinutes(5), held.minusDays(2))) {
                NurseMedicationAdministrationRequestDTO late = givenRequest();
                late.setGivenBeforeHoldAt(wrong);
                late.setGivenBeforeHoldReason("Charted late");
                assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, late)).as(wrong.toString())
                    .hasFieldOrPropertyWithValue("field", "givenBeforeHoldAt");
            }
            verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));

            NurseMedicationAdministrationRequestDTO prior = givenRequest();
            prior.setOverrideReason("No scan at the time");
            LocalDateTime at = held.minusHours(1);
            prior.setGivenBeforeHoldAt(at);
            prior.setGivenBeforeHoldReason("Charted late at handover");
            service.recordMedicationAdministration(rxId, nurseId, hospitalId, prior);

            ArgumentCaptor<MedicationAdministrationRecord> row = ArgumentCaptor.forClass(MedicationAdministrationRecord.class);
            verify(marRepository, org.mockito.Mockito.atLeastOnce()).save(row.capture());
            assertThat(row.getValue().getAdministeredAt()).isEqualTo(at);
            assertThat(row.getValue().getNotes()).contains("Charted late at handover");
            verify(review).doseGivenBeforeHold(rx, at, "Charted late at handover");
        } finally {
            com.example.hms.service.support.OrderReviewHoldGuard.useReview(null);
        }
    }

    /** F8: a finished order an event stands over gives no dose, by a direct POST or a stale panel. */
    @Test
    void f8_aFinishedOrderUnderAnEventGivesNoDose() {
        UUID hospitalId = UUID.randomUUID();
        for (PrescriptionStatus finished : List.of(PrescriptionStatus.PARTNER_DISPENSED, PrescriptionStatus.PRINTED_FOR_PATIENT, PrescriptionStatus.DISPENSED)) {
            Prescription rx = scheduledRx(hospitalId, "toutes les 6 heures");
            rx.setStatus(finished);
            rx.setTakeHome(finished == PrescriptionStatus.DISPENSED);
            when(prescriptionRepository.findByIdForUpdate(rx.getId())).thenReturn(Optional.of(rx));
            com.example.hms.service.OrderReviewHoldService review = mock(com.example.hms.service.OrderReviewHoldService.class);
            when(review.pendingSince(rx)).thenReturn(LocalDateTime.now().minusHours(1));
            com.example.hms.service.support.OrderReviewHoldGuard.useReview(review);
            try {
                UUID rxId = rx.getId();
                UUID nurseId = UUID.randomUUID();
                NurseMedicationAdministrationRequestDTO given = givenRequest();
                assertThatThrownBy(() -> service.recordMedicationAdministration(rxId, nurseId, hospitalId, given)).as(finished.name())
                    .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                    .hasFieldOrPropertyWithValue("messageKey", com.example.hms.service.support.OrderReviewHoldGuard.FINISHED_KEY);
            } finally {
                com.example.hms.service.support.OrderReviewHoldGuard.useReview(null);
            }
        }
        verify(marRepository, never()).save(any(MedicationAdministrationRecord.class));
    }
}
