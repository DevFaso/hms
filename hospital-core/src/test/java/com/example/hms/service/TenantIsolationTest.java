package com.example.hms.service;

import com.example.hms.enums.*;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.*;
import com.example.hms.model.*;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.model.treatment.TreatmentPlan;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderResponseDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.repository.*;
import com.example.hms.service.impl.*;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tenant Isolation Test Suite — Batch 3
 *
 * Validates hospital-scoped (tenant-scoped) services consistently:
 *   1. Return 404 (not 403) when user from Hospital A accesses Hospital B data
 *   2. List/search methods return ONLY data belonging to the active hospital
 *   3. Super-admin (null hospitalId) bypass works correctly
 *
 * Two hospitals (A and B), each with their own entities.
 * RoleValidator is mocked to simulate a user scoped to Hospital A.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tenant Isolation — Cross-Hospital Access Prevention")
class TenantIsolationTest {

    private static final UUID HOSPITAL_A_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_B_ID = UUID.randomUUID();

    // =========================================================================
    //  1. ProcedureOrderService
    // =========================================================================
    @Nested
    @DisplayName("ProcedureOrderService — Tenant Isolation")
    class ProcedureOrderIsolation {
        @Mock private ProcedureOrderRepository procedureOrderRepository;
        @Mock private PatientRepository patientRepository;
        @Mock private HospitalRepository hospitalRepository;
        @Mock private StaffRepository staffRepository;
        @Mock private EncounterRepository encounterRepository;
        @Mock private RoleValidator roleValidator;

        @InjectMocks private ProcedureOrderServiceImpl service;

        private Hospital hospitalA, hospitalB;
        private Patient patientA, patientB;
        private ProcedureOrder orderA, orderB;

        @BeforeEach
        void setUp() {
            hospitalA = new Hospital(); hospitalA.setId(HOSPITAL_A_ID);
            hospitalB = new Hospital(); hospitalB.setId(HOSPITAL_B_ID);
            patientA = new Patient(); patientA.setId(UUID.randomUUID());
            patientB = new Patient(); patientB.setId(UUID.randomUUID());

            orderA = new ProcedureOrder();
            orderA.setId(UUID.randomUUID());
            orderA.setHospital(hospitalA);
            orderA.setPatient(patientA);
            orderA.setProcedureName("Appendectomy");
            orderA.setStatus(ProcedureOrderStatus.SCHEDULED);
            orderA.setOrderedAt(LocalDateTime.now());

            orderB = new ProcedureOrder();
            orderB.setId(UUID.randomUUID());
            orderB.setHospital(hospitalB);
            orderB.setPatient(patientB);
            orderB.setProcedureName("Tonsillectomy");
            orderB.setStatus(ProcedureOrderStatus.SCHEDULED);
            orderB.setOrderedAt(LocalDateTime.now());
        }

        @Test
        @DisplayName("getById — own hospital → success")
        void getById_ownHospital() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(procedureOrderRepository.findById(orderA.getId())).thenReturn(Optional.of(orderA));
            ProcedureOrderResponseDTO result = service.getProcedureOrder(orderA.getId());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("getById — cross-hospital → 404")
        void getById_crossHospital() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(procedureOrderRepository.findById(orderB.getId())).thenReturn(Optional.of(orderB));
            assertThatThrownBy(() -> service.getProcedureOrder(orderB.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("listByPatient — SQL-scoped returns only hospital A data")
        void listByPatient_scoped() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(procedureOrderRepository.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(patientA.getId(), HOSPITAL_A_ID))
                .thenReturn(List.of(orderA));
            assertThat(service.getProcedureOrdersForPatient(patientA.getId())).hasSize(1);
        }

        @Test
        @DisplayName("listByPatient — patient in B, user scoped to A → empty (SQL returns nothing)")
        void listByPatient_crossTenant() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(procedureOrderRepository.findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(patientB.getId(), HOSPITAL_A_ID))
                .thenReturn(List.of());
            assertThat(service.getProcedureOrdersForPatient(patientB.getId())).isEmpty();
        }

        @Test
        @DisplayName("pendingConsent — SQL-scoped to hospital A only")
        void pendingConsent_scoped() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            orderA.setConsentObtained(false);
            when(procedureOrderRepository.findByHospital_IdAndStatusAndConsentObtainedFalse(HOSPITAL_A_ID, ProcedureOrderStatus.SCHEDULED))
                .thenReturn(List.of(orderA));
            assertThat(service.getPendingConsentOrders(HOSPITAL_A_ID)).hasSize(1);
        }

        @Test
        @DisplayName("super-admin (null hospitalId) → getById returns any hospital entity")
        void superAdmin_bypass() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(procedureOrderRepository.findById(orderB.getId())).thenReturn(Optional.of(orderB));
            assertThat(service.getProcedureOrder(orderB.getId())).isNotNull();
        }

        @Test
        @DisplayName("super-admin → listByPatient returns unfiltered (uses findByPatient_Id)")
        void superAdmin_listUnfiltered() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(procedureOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientA.getId()))
                .thenReturn(List.of(orderA));
            assertThat(service.getProcedureOrdersForPatient(patientA.getId())).hasSize(1);
        }
    }

    // =========================================================================
    //  2. DischargeSummaryService
    // =========================================================================
    @Nested
    @DisplayName("DischargeSummaryService — Tenant Isolation")
    class DischargeSummaryIsolation {
        @Mock private DischargeSummaryRepository dischargeSummaryRepository;
        @Mock private DischargeSummaryMapper dischargeSummaryMapper;
        @Mock private PatientRepository patientRepository;
        @Mock private EncounterRepository encounterRepository;
        @Mock private HospitalRepository hospitalRepository;
        @Mock private StaffRepository staffRepository;
        @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
        @Mock private DischargeApprovalRepository dischargeApprovalRepository;
        @Mock private RoleValidator roleValidator;

        @InjectMocks private DischargeSummaryServiceImpl service;

        private Hospital hospitalA, hospitalB;
        private Patient patientA, patientB;
        private DischargeSummary summaryA, summaryB;

        @BeforeEach
        void setUp() {
            hospitalA = new Hospital(); hospitalA.setId(HOSPITAL_A_ID);
            hospitalB = new Hospital(); hospitalB.setId(HOSPITAL_B_ID);
            patientA = new Patient(); patientA.setId(UUID.randomUUID());
            patientB = new Patient(); patientB.setId(UUID.randomUUID());

            summaryA = new DischargeSummary();
            summaryA.setId(UUID.randomUUID());
            summaryA.setHospital(hospitalA);
            summaryA.setPatient(patientA);

            summaryB = new DischargeSummary();
            summaryB.setId(UUID.randomUUID());
            summaryB.setHospital(hospitalB);
            summaryB.setPatient(patientB);
        }

        @Test
        @DisplayName("getById — cross-hospital → 404")
        void getById_crossHospital() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(dischargeSummaryRepository.findById(summaryB.getId())).thenReturn(Optional.of(summaryB));
            assertThatThrownBy(() -> service.getDischargeSummaryById(summaryB.getId(), Locale.ENGLISH))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("listByPatient — SQL-scoped to hospital A")
        void listByPatient_scoped() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(dischargeSummaryRepository.findByPatient_IdAndHospital_IdOrderByDischargeDateDesc(patientA.getId(), HOSPITAL_A_ID))
                .thenReturn(List.of(summaryA));
            DischargeSummaryResponseDTO dto = new DischargeSummaryResponseDTO();
            dto.setId(summaryA.getId());
            when(dischargeSummaryMapper.toResponseDTO(summaryA)).thenReturn(dto);

            var result = service.getDischargeSummariesByPatient(patientA.getId(), Locale.ENGLISH);
            assertThat(result).hasSize(1).first().satisfies(r -> assertThat(r.getId()).isEqualTo(summaryA.getId()));
        }

        @Test
        @DisplayName("listByPatient — patient B, user scoped A → empty")
        void listByPatient_crossTenant() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(dischargeSummaryRepository.findByPatient_IdAndHospital_IdOrderByDischargeDateDesc(patientB.getId(), HOSPITAL_A_ID))
                .thenReturn(List.of());
            assertThat(service.getDischargeSummariesByPatient(patientB.getId(), Locale.ENGLISH)).isEmpty();
        }

        @Test
        @DisplayName("listByProvider — SQL-scoped to hospital A")
        void listByProvider_scoped() {
            UUID providerAId = UUID.randomUUID();
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(dischargeSummaryRepository.findByDischargingProvider_IdAndHospital_IdOrderByDischargeDateDesc(providerAId, HOSPITAL_A_ID))
                .thenReturn(List.of(summaryA));
            DischargeSummaryResponseDTO dto = new DischargeSummaryResponseDTO();
            when(dischargeSummaryMapper.toResponseDTO(summaryA)).thenReturn(dto);

            assertThat(service.getDischargeSummariesByProvider(providerAId, Locale.ENGLISH)).hasSize(1);
        }
    }

    // =========================================================================
    //  3. GeneralReferralService
    // =========================================================================
    @Nested
    @DisplayName("GeneralReferralService — Tenant Isolation")
    class GeneralReferralIsolation {
        @Mock private GeneralReferralRepository referralRepository;
        @Mock private PatientRepository patientRepository;
        @Mock private HospitalRepository hospitalRepository;
        @Mock private StaffRepository staffRepository;
        @Mock private DepartmentRepository departmentRepository;
        @Mock private RoleValidator roleValidator;

        @InjectMocks private GeneralReferralServiceImpl service;

        private Hospital hospitalA, hospitalB;
        private Patient patientA;
        private Staff referringProviderA;
        private GeneralReferral referralA, referralB;

        @BeforeEach
        void setUp() {
            hospitalA = new Hospital(); hospitalA.setId(HOSPITAL_A_ID);
            hospitalB = new Hospital(); hospitalB.setId(HOSPITAL_B_ID);
            patientA = new Patient(); patientA.setId(UUID.randomUUID());

            referringProviderA = new Staff();
            referringProviderA.setId(UUID.randomUUID());
            referringProviderA.setHospital(hospitalA);

            referralA = new GeneralReferral();
            referralA.setId(UUID.randomUUID());
            referralA.setHospital(hospitalA);
            referralA.setPatient(patientA);
            referralA.setReferringProvider(referringProviderA);

            referralB = new GeneralReferral();
            referralB.setId(UUID.randomUUID());
            referralB.setHospital(hospitalB);
        }

        @Test
        @DisplayName("getById — cross-hospital referral → 404")
        void getById_crossHospital() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(referralRepository.findById(referralB.getId())).thenReturn(Optional.of(referralB));
            assertThatThrownBy(() -> service.getReferral(referralB.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("listByPatient — SQL-scoped to hospital A")
        void listByPatient_scoped() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(referralRepository.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patientA.getId(), HOSPITAL_A_ID))
                .thenReturn(List.of(referralA));
            // Service uses private toResponse() — no mapper mock needed
            assertThat(service.getReferralsByPatient(patientA.getId())).hasSize(1);
        }

        @Test
        @DisplayName("listByPatient — cross-tenant → empty")
        void listByPatient_crossTenant() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            UUID crossPatientId = UUID.randomUUID();
            when(referralRepository.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(crossPatientId, HOSPITAL_A_ID))
                .thenReturn(List.of());
            assertThat(service.getReferralsByPatient(crossPatientId)).isEmpty();
        }
    }

    // =========================================================================
    //  4. TreatmentPlanService
    // =========================================================================
    @Nested
    @DisplayName("TreatmentPlanService — Tenant Isolation")
    class TreatmentPlanIsolation {
        @Mock private TreatmentPlanRepository treatmentPlanRepository;
        @Mock private TreatmentPlanFollowUpRepository followUpRepository;
        @Mock private TreatmentPlanReviewRepository reviewRepository;
        @Mock private PatientRepository patientRepository;
        @Mock private HospitalRepository hospitalRepository;
        @Mock private EncounterRepository encounterRepository;
        @Mock private StaffRepository staffRepository;
        @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
        @Mock private TreatmentPlanMapper treatmentPlanMapper;
        @Mock private RoleValidator roleValidator;

        @InjectMocks private TreatmentPlanServiceImpl service;

        private Hospital hospitalA, hospitalB;
        private TreatmentPlan planA, planB;

        @BeforeEach
        void setUp() {
            hospitalA = new Hospital(); hospitalA.setId(HOSPITAL_A_ID);
            hospitalB = new Hospital(); hospitalB.setId(HOSPITAL_B_ID);

            planA = new TreatmentPlan();
            planA.setId(UUID.randomUUID());
            planA.setHospital(hospitalA);

            planB = new TreatmentPlan();
            planB.setId(UUID.randomUUID());
            planB.setHospital(hospitalB);
        }

        @Test
        @DisplayName("getById — own hospital → success")
        void getById_ownHospital() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(treatmentPlanRepository.findById(planA.getId())).thenReturn(Optional.of(planA));
            TreatmentPlanResponseDTO dto = new TreatmentPlanResponseDTO();
            dto.setId(planA.getId());
            when(treatmentPlanMapper.toResponseDTO(planA)).thenReturn(dto);
            assertThat(service.getById(planA.getId()).getId()).isEqualTo(planA.getId());
        }

        @Test
        @DisplayName("getById — cross-hospital → 404")
        void getById_crossHospital() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(treatmentPlanRepository.findById(planB.getId())).thenReturn(Optional.of(planB));
            assertThatThrownBy(() -> service.getById(planB.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("listByHospital — scoped to A, B data excluded")
        void listByHospital_scoped() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            when(treatmentPlanRepository.findAllByHospitalId(eq(HOSPITAL_A_ID), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(planA)));
            TreatmentPlanResponseDTO dto = new TreatmentPlanResponseDTO();
            when(treatmentPlanMapper.toResponseDTO(planA)).thenReturn(dto);

            var result = service.listByHospital(HOSPITAL_A_ID, null,
                org.springframework.data.domain.PageRequest.of(0, 20));
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("super-admin (null) → getById returns any hospital entity")
        void superAdmin_bypass() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(treatmentPlanRepository.findById(planB.getId())).thenReturn(Optional.of(planB));
            TreatmentPlanResponseDTO dto = new TreatmentPlanResponseDTO();
            when(treatmentPlanMapper.toResponseDTO(planB)).thenReturn(dto);
            assertThat(service.getById(planB.getId())).isNotNull();
        }
    }

    // =========================================================================
    //  5. WebSocket Isolation — Notification routed to user destination
    // =========================================================================
    @Nested
    @DisplayName("NotificationWebSocket — User-Scoped Delivery")
    class NotificationWebSocketIsolation {
        @Mock private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

        private com.example.hms.controller.NotificationWebSocketController controller;

        @BeforeEach
        void setUp() {
            controller = new com.example.hms.controller.NotificationWebSocketController(messagingTemplate);
        }

        @Test
        @DisplayName("notification with recipientUsername → sent to user destination only")
        void userScopedDelivery() {
            Notification notification = Notification.builder()
                .message("Lab results ready")
                .recipientUsername("doctorA")
                .build();

            controller.sendNotification(notification);

            verify(messagingTemplate).convertAndSendToUser(
                eq("doctorA"), eq("/topic/notifications"), eq(notification));
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("notification without recipientUsername → broadcast fallback")
        void broadcastFallback() {
            Notification notification = Notification.builder()
                .message("System maintenance in 1 hour")
                .build();

            controller.sendNotification(notification);

            verify(messagingTemplate).convertAndSend("/topic/notifications", notification);
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }
    }
}
