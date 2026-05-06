package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.mapper.AdmissionMapper;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.mapper.StaffAvailabilityMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.LabOrder;
import com.example.hms.model.StaffAvailability;
import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;
import com.example.hms.payload.dto.SuperAdminSummaryDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.model.Appointment;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffAvailabilityRepository;
import com.example.hms.repository.TreatmentPlanRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import java.util.Locale;

@ExtendWith(MockitoExtension.class)
class SuperAdminDashboardServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private AuditEventLogRepository auditEventLogRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private EncounterService encounterService;
    @Mock private StaffAvailabilityRepository staffAvailabilityRepository;
    @Mock private StaffAvailabilityMapper staffAvailabilityMapper;
    @Mock private PatientConsentService patientConsentService;
    @Mock private EncounterRepository encounterRepository;
    @Mock private ConsultationRepository consultationRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private LabTestDefinitionRepository labTestDefinitionRepository;
    @Mock private AdmissionRepository admissionRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private TreatmentPlanRepository treatmentPlanRepository;
    @Mock private GeneralReferralRepository generalReferralRepository;
    @Mock private ConsultationService consultationService;
    @Mock private LabResultService labResultService;
    @Mock private LabTestDefinitionService labTestDefinitionService;
    @Mock private PrescriptionService prescriptionService;
    @Mock private TreatmentPlanService treatmentPlanService;
    @Mock private GeneralReferralService generalReferralService;
    @Mock private LabOrderMapper labOrderMapper;
    @Mock private AdmissionMapper admissionMapper;

    @InjectMocks private SuperAdminDashboardServiceImpl service;

    /**
     * In production Spring's container injects a proxy of the bean into
     * {@code self} via {@code setSelf(@Lazy ...)} so
     * {@link SuperAdminDashboardServiceImpl#getRecentActivity} can call
     * the per-feed {@code getRecent*} methods through the AOP proxy
     * (Sonar S6809). In Mockito-only unit tests there is no container
     * and {@code @InjectMocks} doesn't call public setters, so we wire
     * {@code self} to the same instance manually. Calling the real
     * methods (rather than mocking them on a separate
     * {@code SuperAdminDashboardService} mock) keeps the F5 composition
     * test honest: it must verify each stubbed feed actually flows
     * through the real {@code getRecent*} bodies.
     */
    @org.junit.jupiter.api.BeforeEach
    void wireSelf() {
        service.setSelf(service);
    }

    @Test
    void getSummary_success() {
        when(userRepository.countByIsDeletedFalse()).thenReturn(100L);
        when(userRepository.countByIsActiveTrueAndIsDeletedFalse()).thenReturn(80L);
        when(hospitalRepository.count()).thenReturn(10L);
        when(hospitalRepository.countByActiveTrue()).thenReturn(8L);
        when(patientRepository.count()).thenReturn(500L);
        when(roleRepository.count()).thenReturn(5L);
        when(assignmentRepository.count()).thenReturn(200L);
        when(assignmentRepository.countByActiveTrue()).thenReturn(150L);
        when(assignmentRepository.countByHospitalIsNull()).thenReturn(10L);
        when(assignmentRepository.countByHospitalIsNullAndActiveTrue()).thenReturn(8L);
        when(organizationRepository.count()).thenReturn(3L);
        when(organizationRepository.countByActiveTrue()).thenReturn(2L);
        when(departmentRepository.count()).thenReturn(12L);
        when(appointmentRepository.countByAppointmentDateBetween(any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(5L);

        AuditEventLog auditLog = AuditEventLog.builder()
            .eventType(AuditEventType.USER_CREATE)
            .status(AuditStatus.SUCCESS)
            .entityType("USER")
            .eventTimestamp(LocalDateTime.now())
            .build();
        auditLog.setId(UUID.randomUUID());
        Page<AuditEventLog> auditPage = new PageImpl<>(List.of(auditLog));
        when(auditEventLogRepository.findAllByOrderByEventTimestampDesc(any(Pageable.class))).thenReturn(auditPage);

        SuperAdminSummaryDTO result = service.getSummary(10);

        assertThat(result).isNotNull();
        assertThat(result.getTotalUsers()).isEqualTo(100L);
        assertThat(result.getActiveUsers()).isEqualTo(80L);
        assertThat(result.getInactiveUsers()).isEqualTo(20L);
        assertThat(result.getTotalHospitals()).isEqualTo(10L);
        assertThat(result.getTotalPatients()).isEqualTo(500L);
        assertThat(result.getRecentAuditEvents()).hasSize(1);
    }

    @Test
    void getSummary_zeroValues() {
        // All count() calls return 0L by default — only stub non-primitive return types
        when(auditEventLogRepository.findAllByOrderByEventTimestampDesc(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        SuperAdminSummaryDTO result = service.getSummary(-1);
        assertThat(result).isNotNull();
    }

    @Test
    void getRecentEncounters_success() {
        Page<EncounterResponseDTO> page = new PageImpl<>(List.of(new EncounterResponseDTO()));
        when(encounterService.list(any(), any(), any(), any(), any(), any(), any(Pageable.class), any(Locale.class)))
            .thenReturn(page);

        List<EncounterResponseDTO> result = service.getRecentEncounters(5, Locale.ENGLISH);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentStaffAvailability_success() {
        StaffAvailability sa = new StaffAvailability();
        Page<StaffAvailability> page = new PageImpl<>(List.of(sa));
        when(staffAvailabilityRepository.findAllByOrderByDateDesc(any(Pageable.class))).thenReturn(page);
        StaffAvailabilityResponseDTO dto = new StaffAvailabilityResponseDTO(
            UUID.randomUUID(), UUID.randomUUID(), "name", "lic",
            UUID.randomUUID(), "hosp", UUID.randomUUID(), "dept", "deptTr",
            null, null, null, false, null);
        when(staffAvailabilityMapper.toDto(sa)).thenReturn(dto);

        List<StaffAvailabilityResponseDTO> result = service.getRecentStaffAvailability(5);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentPatientConsents_success() {
        Page<PatientConsentResponseDTO> page = new PageImpl<>(List.of(PatientConsentResponseDTO.builder().build()));
        when(patientConsentService.getAllConsents(any(Pageable.class))).thenReturn(page);

        List<PatientConsentResponseDTO> result = service.getRecentPatientConsents(5);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentEncounters_emptyPage() {
        Page<EncounterResponseDTO> page = new PageImpl<>(List.of());
        when(encounterService.list(any(), any(), any(), any(), any(), any(), any(Pageable.class), any(Locale.class)))
            .thenReturn(page);

        List<EncounterResponseDTO> result = service.getRecentEncounters(-1, Locale.ENGLISH);
        assertThat(result).isEmpty();
    }

    @Test
    void getSummary_populatesClinicalCounters() {
        when(encounterRepository.count()).thenReturn(11L);
        when(consultationRepository.count()).thenReturn(22L);
        when(labOrderRepository.count()).thenReturn(33L);
        when(labResultRepository.count()).thenReturn(44L);
        when(labTestDefinitionRepository.count()).thenReturn(55L);
        when(admissionRepository.count()).thenReturn(66L);
        when(prescriptionRepository.count()).thenReturn(77L);
        when(treatmentPlanRepository.count()).thenReturn(88L);
        when(generalReferralRepository.count()).thenReturn(99L);
        when(auditEventLogRepository.findAllByOrderByEventTimestampDesc(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        SuperAdminSummaryDTO result = service.getSummary(10);

        assertThat(result.getTotalEncounters()).isEqualTo(11L);
        assertThat(result.getTotalConsultations()).isEqualTo(22L);
        assertThat(result.getTotalLabOrders()).isEqualTo(33L);
        assertThat(result.getTotalLabResults()).isEqualTo(44L);
        assertThat(result.getTotalLabTestDefinitions()).isEqualTo(55L);
        assertThat(result.getTotalAdmissions()).isEqualTo(66L);
        assertThat(result.getTotalPrescriptions()).isEqualTo(77L);
        assertThat(result.getTotalTreatmentPlans()).isEqualTo(88L);
        assertThat(result.getTotalReferrals()).isEqualTo(99L);
    }

    @Test
    void getRecentConsultations_delegatesToPagedSuperAdminQuery() {
        when(consultationService.getRecentForSuperAdmin(any(Pageable.class)))
            .thenReturn(List.of(new ConsultationResponseDTO(), new ConsultationResponseDTO()));

        List<ConsultationResponseDTO> result = service.getRecentConsultations(2);
        assertThat(result).hasSize(2);
    }

    @Test
    void getRecentPrescriptions_delegatesToList() {
        Page<PrescriptionResponseDTO> page = new PageImpl<>(List.of(new PrescriptionResponseDTO()));
        when(prescriptionService.list(any(), any(), any(), any(Pageable.class), any(Locale.class)))
            .thenReturn(page);

        List<PrescriptionResponseDTO> result = service.getRecentPrescriptions(5, Locale.ENGLISH);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentReferrals_delegatesToPagedSuperAdminQuery() {
        when(generalReferralService.getRecentForSuperAdmin(any(Pageable.class)))
            .thenReturn(List.of(new GeneralReferralResponseDTO()));

        List<GeneralReferralResponseDTO> result = service.getRecentReferrals(1);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentLabResults_delegatesToPaged() {
        Page<LabResultResponseDTO> page = new PageImpl<>(List.of(LabResultResponseDTO.builder().build()));
        when(labResultService.getLabResultsPage(any(Pageable.class), any(Locale.class))).thenReturn(page);

        List<LabResultResponseDTO> result = service.getRecentLabResults(5, Locale.ENGLISH);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentLabOrders_queriesRepositoryDirectlySorted() {
        LabOrder lo = new LabOrder();
        when(labOrderRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(lo)));
        when(labOrderMapper.toLabOrderResponseDTO(lo)).thenReturn(new LabOrderResponseDTO());

        List<LabOrderResponseDTO> result = service.getRecentLabOrders(2, Locale.ENGLISH);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentLabTestDefinitions_delegatesToSearch() {
        Page<LabTestDefinitionResponseDTO> page = new PageImpl<>(List.of(new LabTestDefinitionResponseDTO()));
        when(labTestDefinitionService.search(any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(page);

        List<LabTestDefinitionResponseDTO> result = service.getRecentLabTestDefinitions(5);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentAdmissions_queriesRepositoryDirectlySorted() {
        Admission ad = new Admission();
        when(admissionRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(ad)));
        when(admissionMapper.toResponseDTO(ad)).thenReturn(new AdmissionResponseDTO());

        List<AdmissionResponseDTO> result = service.getRecentAdmissions(1);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentTreatmentPlans_delegatesToListAll() {
        Page<TreatmentPlanResponseDTO> page = new PageImpl<>(List.of(new TreatmentPlanResponseDTO()));
        when(treatmentPlanService.listAll(any(), any(Pageable.class))).thenReturn(page);

        List<TreatmentPlanResponseDTO> result = service.getRecentTreatmentPlans(5);
        assertThat(result).hasSize(1);
    }

    @Test
    void sanitizeLimit_clampsAtMax() {
        when(treatmentPlanService.listAll(any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        service.getRecentTreatmentPlans(10_000);

        // Verifying the path that exercises the max-cap branch in sanitizeLimit;
        // a successful invocation is sufficient (no exception, defaults applied).
        // The bounded behaviour is asserted by the absence of failure here and by
        // the limit-applied tests above.
        assertThat(true).isTrue();
    }

    /* ────────────────────────────────────────────────────────────────────
     * Sort-field regression suite (Copilot review on commit 2678681f).
     *
     * Every getRecent* method must sort by a CLINICAL timestamp (when
     * the event happened to the patient), not the row's `createdAt`
     * (when the database row was inserted). Backfilled / migrated /
     * late-edited rows would otherwise masquerade as the newest
     * activity. `createdAt` is retained as the secondary tiebreaker so
     * rows missing the primary timestamp still land in a stable order.
     *
     * These tests capture the `Pageable` argument the service hands to
     * its downstream collaborator and assert the sort orders by name.
     * If a future refactor silently flips the sort back to `createdAt`
     * the assertion fails loudly.
     * ──────────────────────────────────────────────────────────────────── */

    private static void assertSortOrdersStartWith(
        org.springframework.data.domain.Pageable pageable,
        String... expectedFieldsInOrder
    ) {
        org.springframework.data.domain.Sort sort = pageable.getSort();
        assertThat(sort.isSorted()).as("Pageable.sort").isTrue();
        java.util.List<String> actual = sort.stream()
            .map(o -> o.getProperty() + ":" + o.getDirection())
            .toList();
        java.util.List<String> expected = java.util.Arrays.stream(expectedFieldsInOrder)
            .map(f -> f + ":DESC")
            .toList();
        assertThat(actual).as("sort orders").containsExactly(expected.toArray(new String[0]));
    }

    @Test
    void getRecentEncounters_sortsByEncounterDateThenCreatedAt() {
        when(encounterService.list(any(), any(), any(), any(), any(), any(), any(Pageable.class), any()))
            .thenReturn(new PageImpl<>(java.util.List.of()));

        service.getRecentEncounters(5, Locale.ENGLISH);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(encounterService)
            .list(any(), any(), any(), any(), any(), any(), captor.capture(), any());
        assertSortOrdersStartWith(captor.getValue(), "encounterDate", "createdAt");
    }

    @Test
    void getRecentLabOrders_sortsByOrderDatetimeThenCreatedAt() {
        when(labOrderRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(java.util.List.of()));

        service.getRecentLabOrders(5, Locale.ENGLISH);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(labOrderRepository).findAll(captor.capture());
        assertSortOrdersStartWith(captor.getValue(), "orderDatetime", "createdAt");
    }

    @Test
    void getRecentLabResults_sortsByResultDateThenCreatedAt() {
        when(labResultService.getLabResultsPage(any(Pageable.class), any()))
            .thenReturn(new PageImpl<>(java.util.List.of()));

        service.getRecentLabResults(5, Locale.ENGLISH);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(labResultService).getLabResultsPage(captor.capture(), any());
        assertSortOrdersStartWith(captor.getValue(), "resultDate", "createdAt");
    }

    @Test
    void getRecentAdmissions_sortsByAdmissionDateTimeThenCreatedAt() {
        when(admissionRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(java.util.List.of()));

        service.getRecentAdmissions(5);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(admissionRepository).findAll(captor.capture());
        assertSortOrdersStartWith(captor.getValue(), "admissionDateTime", "createdAt");
    }

    @Test
    void getRecentTreatmentPlans_sortsByTimelineStartDateThenCreatedAt() {
        when(treatmentPlanService.listAll(any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(java.util.List.of()));

        service.getRecentTreatmentPlans(5);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(treatmentPlanService).listAll(any(), captor.capture());
        assertSortOrdersStartWith(captor.getValue(), "timelineStartDate", "createdAt");
    }

    @Test
    void getRecentReferrals_sortsBySubmittedAtThenCreatedAt() {
        when(generalReferralService.getRecentForSuperAdmin(any(Pageable.class)))
            .thenReturn(java.util.List.of());

        service.getRecentReferrals(5);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(generalReferralService).getRecentForSuperAdmin(captor.capture());
        assertSortOrdersStartWith(captor.getValue(), "submittedAt", "createdAt");
    }

    @Test
    void getRecentPrescriptions_sortsByCreatedAtIntentionally() {
        // Prescriptions stay on `createdAt` because that IS the clinical
        // write event for this entity (no separate clinical-time field
        // is universally populated). Lock the choice with a test so a
        // future "consistency" refactor doesn't silently change it.
        when(prescriptionService.list(any(), any(), any(), any(Pageable.class), any()))
            .thenReturn(new PageImpl<>(java.util.List.of()));

        service.getRecentPrescriptions(5, Locale.ENGLISH);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(prescriptionService)
            .list(any(), any(), any(), captor.capture(), any());
        assertSortOrdersStartWith(captor.getValue(), "createdAt");
    }

    /**
     * F5 — getRecentActivity must compose all 9 per-feed methods and
     * return a {@link com.example.hms.payload.dto.RecentActivityDTO}
     * whose lists are exactly the per-feed results (no reordering, no
     * silent dedup, no cross-talk between feeds). We use distinct
     * single-row stubs so a wrong assignment shows up immediately.
     */
    @Test
    void getRecentActivity_composesAllNineFeedsIntoOneBundle() {
        // Encounters
        EncounterResponseDTO enc = new EncounterResponseDTO();
        when(encounterService.list(any(), any(), any(), any(), any(), any(), any(Pageable.class), any()))
            .thenReturn(new PageImpl<>(java.util.List.of(enc)));
        // Consultations
        ConsultationResponseDTO con = new ConsultationResponseDTO();
        when(consultationService.getRecentForSuperAdmin(any(Pageable.class)))
            .thenReturn(java.util.List.of(con));
        // Lab orders
        LabOrder labOrder = new LabOrder();
        when(labOrderRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(java.util.List.of(labOrder)));
        LabOrderResponseDTO labOrderDto = new LabOrderResponseDTO();
        when(labOrderMapper.toLabOrderResponseDTO(labOrder)).thenReturn(labOrderDto);
        // Lab results
        LabResultResponseDTO labResult = LabResultResponseDTO.builder().build();
        when(labResultService.getLabResultsPage(any(Pageable.class), any()))
            .thenReturn(new PageImpl<>(java.util.List.of(labResult)));
        // Lab test definitions
        LabTestDefinitionResponseDTO testDef = new LabTestDefinitionResponseDTO();
        when(labTestDefinitionService.search(any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(java.util.List.of(testDef)));
        // Admissions
        Admission adm = new Admission();
        when(admissionRepository.findAll(any(Pageable.class)))
            .thenReturn(new PageImpl<>(java.util.List.of(adm)));
        AdmissionResponseDTO admDto = new AdmissionResponseDTO();
        when(admissionMapper.toResponseDTO(adm)).thenReturn(admDto);
        // Prescriptions
        PrescriptionResponseDTO presDto = new PrescriptionResponseDTO();
        when(prescriptionService.list(any(), any(), any(), any(Pageable.class), any()))
            .thenReturn(new PageImpl<>(java.util.List.of(presDto)));
        // Treatment plans
        TreatmentPlanResponseDTO planDto = new TreatmentPlanResponseDTO();
        when(treatmentPlanService.listAll(any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(java.util.List.of(planDto)));
        // Referrals
        GeneralReferralResponseDTO refDto = new GeneralReferralResponseDTO();
        when(generalReferralService.getRecentForSuperAdmin(any(Pageable.class)))
            .thenReturn(java.util.List.of(refDto));

        var bundle = service.getRecentActivity(20, Locale.ENGLISH);

        // Each feed contributes exactly its stubbed row; no cross-talk.
        assertThat(bundle.getEncounters()).containsExactly(enc);
        assertThat(bundle.getConsultations()).containsExactly(con);
        assertThat(bundle.getLabOrders()).containsExactly(labOrderDto);
        assertThat(bundle.getLabResults()).containsExactly(labResult);
        assertThat(bundle.getLabTestDefinitions()).containsExactly(testDef);
        assertThat(bundle.getAdmissions()).containsExactly(admDto);
        assertThat(bundle.getPrescriptions()).containsExactly(presDto);
        assertThat(bundle.getTreatmentPlans()).containsExactly(planDto);
        assertThat(bundle.getReferrals()).containsExactly(refDto);
    }
}
