package com.example.hms.service;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ImagingOrderMapper;
import com.example.hms.mapper.ImagingReportMapper;
import com.example.hms.mapper.UltrasoundMapper;
import com.example.hms.model.Consultation;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.Patient;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.repository.UltrasoundOrderRepository;
import com.example.hms.repository.UltrasoundReportRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.impl.ConsultationServiceImpl;
import com.example.hms.service.impl.ImagingOrderServiceImpl;
import com.example.hms.service.impl.ImagingReportServiceImpl;
import com.example.hms.service.impl.ProcedureOrderServiceImpl;
import com.example.hms.service.impl.UltrasoundServiceImpl;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Staff who are also patients (#754's rule) on the consultation, imaging,
 * procedure and ultrasound reads: a nurse working at hospital A who was a
 * patient at hospital B reads her OWN rows from B — as their patient (released
 * reports only, patient copy) — while a stranger's row at B still answers
 * exactly as a missing id, and a nurse whose account is linked to the patient
 * row but who does NOT hold {@code ROLE_PATIENT} is still held to A: the link
 * is a fact about the account, not a grant.
 *
 * <p>Every service here runs with the REAL {@link PatientSubjectReadGuard} over
 * a real {@link ControllerAuthUtils}.
 */
@DisplayName("Staff who are also patients read their own rows from another hospital")
class StaffWhoArePatientsReadTest {

    private final UUID callerUserId = UUID.randomUUID();
    private final UUID ownPatientId = UUID.randomUUID();
    private final UUID strangerPatientId = UUID.randomUUID();
    private final Hospital hospitalA = hospital(UUID.randomUUID());
    private final Hospital hospitalB = hospital(UUID.randomUUID());

    private final PatientRepository guardPatients = mock(PatientRepository.class);

    private PatientSubjectReadGuard realGuard() {
        return new PatientSubjectReadGuard(
            new ControllerAuthUtils(mock(UserRoleHospitalAssignmentRepository.class)), guardPatients);
    }

    @BeforeEach
    void linkTheCallerToTheirRow() {
        when(guardPatients.existsByIdAndUserId(ownPatientId, callerUserId)).thenReturn(true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(String... roles) {
        var authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        var principal = new CustomUserDetails(callerUserId, "caller", "pw", true, authorities);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    /** The nurse-who-is-a-patient over Keycloak: the user id is the appUserId claim. */
    private void keycloakLogin(String... roles) {
        var authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
            .claim("sub", "keycloak-subject").claim("appUserId", callerUserId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    private void nurseWhoIsAPatient() {
        login("ROLE_NURSE", "ROLE_PATIENT");
    }

    /** Linked to the patient row, but holding no ROLE_PATIENT. */
    private void nurseLinkedWithoutTheGrant() {
        login("ROLE_NURSE");
    }

    private static Patient patient(UUID id) {
        Patient p = new Patient();
        p.setId(id);
        return p;
    }

    private static Hospital hospital(UUID id) {
        Hospital h = new Hospital();
        h.setId(id);
        return h;
    }

    private static void assertSameNotFound(Executable refused, Executable missing) {
        ResourceNotFoundException a = catchThrowableOfType(refused::execute, ResourceNotFoundException.class);
        ResourceNotFoundException b = catchThrowableOfType(missing::execute, ResourceNotFoundException.class);
        assertThat(a).as("the refused id must answer 404").isNotNull();
        assertThat(b).as("the unknown id must answer 404").isNotNull();
        assertThat(a.getMessageKey()).isEqualTo(b.getMessageKey());
        assertThat(a.getMessage()).isEqualTo(b.getMessage());
    }

    // ── guard ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PatientSubjectReadGuard.ownsAsItsPatient")
    class Guard {
        private final PatientSubjectReadGuard guard = realGuard();

        @Test
        @DisplayName("true only with ROLE_PATIENT AND ownership, on either login path")
        void bothHalves() {
            nurseWhoIsAPatient();
            assertThat(guard.ownsAsItsPatient(patient(ownPatientId))).isTrue();
            assertThat(guard.ownsAsItsPatient(ownPatientId)).isTrue();
            assertThat(guard.ownsAsItsPatient(patient(strangerPatientId))).isFalse();
            assertThat(guard.ownsAsItsPatient((Patient) null)).isFalse();

            keycloakLogin("ROLE_NURSE", "ROLE_PATIENT");
            assertThat(guard.ownsAsItsPatient(ownPatientId)).isTrue();

            nurseLinkedWithoutTheGrant();
            assertThat(guard.ownsAsItsPatient(patient(ownPatientId))).isFalse();
            assertThat(guard.ownsAsItsPatient(ownPatientId)).isFalse();
        }

        @Test
        @DisplayName("a caller without ROLE_PATIENT never pays the ownership query")
        void noQueryWithoutTheGrant() {
            nurseLinkedWithoutTheGrant();
            guard.ownsAsItsPatient(ownPatientId);
            verify(guardPatients, never()).existsByIdAndUserId(any(), any());
        }
    }

    // ── consultations ──────────────────────────────────────────────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("GET /consultations/patient/{patientId} and /me/patient/consultations")
    class Consultations {
        @Mock private ConsultationRepository consultationRepository;
        @Mock private RoleValidator roleValidator;
        @Mock private RecordAccessPolicy recordAccessPolicy;
        @Mock private CrossHospitalReachRecorder reachRecorder;
        @Mock private com.example.hms.service.recordaccess.BreakGlassGate breakGlassGate;
        @Mock private com.example.hms.service.recordaccess.SensitivityClassifier sensitivityClassifier;
        @Spy private PatientSubjectReadGuard subjectReadGuard = realGuard();
        @InjectMocks private ConsultationServiceImpl service;

        @BeforeEach
        void actingAtA() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA.getId());
            when(recordAccessPolicy.readableHospitalIds(any(), any(), any())).thenReturn(Set.of(hospitalA.getId()));
        }

        @Test
        @DisplayName("the nurse reads her own consultations at every hospital, as their patient, with no reach recorded")
        void ownEverywhere() {
            nurseWhoIsAPatient();
            Consultation atB = Consultation.builder().hospital(hospitalB).patient(patient(ownPatientId)).build();
            when(consultationRepository.findByPatient_IdOrderByRequestedAtDesc(ownPatientId)).thenReturn(List.of(atB));

            assertThat(service.getConsultationsForPatient(ownPatientId)).hasSize(1);
            verify(consultationRepository, never()).findByPatient_IdAndHospital_IdInOrderByRequestedAtDesc(any(), any());
            verifyNoInteractions(reachRecorder, recordAccessPolicy);
        }

        @Test
        @DisplayName("a stranger's, or her own without the patient grant, stays on the staff branch")
        void othersStayOnTheStaffBranch() {
            nurseWhoIsAPatient();
            service.getConsultationsForPatient(strangerPatientId);
            nurseLinkedWithoutTheGrant();
            service.getConsultationsForPatient(ownPatientId);

            verify(consultationRepository, never()).findByPatient_IdOrderByRequestedAtDesc(any());
            verify(consultationRepository).findByPatient_IdAndHospital_IdInOrderByRequestedAtDesc(strangerPatientId, Set.of(hospitalA.getId()));
            verify(consultationRepository).findByPatient_IdAndHospital_IdInOrderByRequestedAtDesc(ownPatientId, Set.of(hospitalA.getId()));
        }
    }

    // ── imaging ────────────────────────────────────────────────────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("GET /imaging/orders/patient/{patientId}")
    class ImagingOrders {
        @Mock private ImagingOrderRepository imagingOrderRepository;
        @Mock private ImagingOrderMapper imagingOrderMapper;
        @Mock private RoleValidator roleValidator;
        @Mock private RecordAccessPolicy recordAccessPolicy;
        @Mock private CrossHospitalReachRecorder reachRecorder;
        @Spy private PatientSubjectReadGuard subjectReadGuard = realGuard();
        @InjectMocks private ImagingOrderServiceImpl service;

        @Test
        @DisplayName("the nurse reads her own imaging orders at every hospital; without the grant she is held to A")
        void ownEverywhere() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA.getId());
            when(recordAccessPolicy.readableHospitalIds(any(), any(), any())).thenReturn(Set.of(hospitalA.getId()));

            nurseWhoIsAPatient();
            service.getOrdersByPatient(ownPatientId, null);
            verify(imagingOrderRepository).findByPatient_IdOrderByOrderedAtDesc(ownPatientId);
            verifyNoInteractions(reachRecorder);

            nurseLinkedWithoutTheGrant();
            service.getOrdersByPatient(ownPatientId, null);
            verify(imagingOrderRepository).findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(ownPatientId, Set.of(hospitalA.getId()));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("GET /imaging/results/{reportId} and /imaging/results/order/{orderId}")
    class ImagingReports {
        @Mock private ImagingReportRepository imagingReportRepository;
        @Mock private ImagingOrderRepository imagingOrderRepository;
        @Mock private ImagingReportMapper imagingReportMapper;
        @Mock private RoleValidator roleValidator;
        @Spy private PatientSubjectReadGuard subjectReadGuard = realGuard();
        @InjectMocks private ImagingReportServiceImpl service;

        private final UUID reportId = UUID.randomUUID();
        private final UUID orderId = UUID.randomUUID();
        private final ImagingReportResponseDTO dto = new ImagingReportResponseDTO();

        @BeforeEach
        void actingAtA() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA.getId());
        }

        private ImagingReport reportAtB(UUID patientId, boolean signed) {
            ImagingOrder order = new ImagingOrder();
            order.setId(orderId);
            order.setPatient(patient(patientId));
            order.setHospital(hospitalB);
            ImagingReport report = new ImagingReport();
            report.setId(reportId);
            report.setImagingOrder(order);
            report.setHospital(hospitalB);
            report.setReportStatus(signed ? ImagingReportStatus.FINAL : ImagingReportStatus.PRELIMINARY);
            report.setSignedAt(signed ? LocalDateTime.now() : null);
            when(imagingReportRepository.findById(reportId)).thenReturn(Optional.of(report));
            when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(imagingReportRepository.findFirstByImagingOrder_IdAndLatestVersionIsTrue(orderId)).thenReturn(Optional.of(report));
            when(imagingReportMapper.toResponseDTO(report)).thenReturn(dto);
            return report;
        }

        private void nothing() {
            when(imagingReportRepository.findById(reportId)).thenReturn(Optional.empty());
            when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("her own signed report at B reads, by id and by order")
        void ownSignedReads() {
            nurseWhoIsAPatient();
            reportAtB(ownPatientId, true);
            assertThat(service.getReport(reportId)).isSameAs(dto);
            assertThat(service.getLatestReportForOrder(orderId)).isSameAs(dto);
        }

        @Test
        @DisplayName("her own UNSIGNED report at B answers as a missing one: she reads as its patient")
        void ownUnsignedIsWithheld() {
            nurseWhoIsAPatient();
            reportAtB(ownPatientId, false);
            ResourceNotFoundException byId = catchThrowableOfType(() -> service.getReport(reportId), ResourceNotFoundException.class);
            ResourceNotFoundException byOrder = catchThrowableOfType(() -> service.getLatestReportForOrder(orderId), ResourceNotFoundException.class);
            assertThat(byId).isNotNull();
            assertThat(byOrder).isNotNull();
        }

        @Test
        @DisplayName("a stranger's report at B, or her own without the grant, answers exactly as a missing one")
        void othersAnswerAsMissing() {
            nurseWhoIsAPatient();
            reportAtB(strangerPatientId, true);
            ImagingReportServiceImpl s = service;
            assertSameNotFound(() -> s.getReport(reportId), () -> { nothing(); s.getReport(reportId); });

            nurseLinkedWithoutTheGrant();
            reportAtB(ownPatientId, true);
            assertSameNotFound(() -> s.getLatestReportForOrder(orderId), () -> { nothing(); s.getLatestReportForOrder(orderId); });
        }

        @Test
        @DisplayName("a pure patient reads their own signed report wherever it was written")
        void purePatientOwnElsewhere() {
            login("ROLE_PATIENT");
            reportAtB(ownPatientId, true);
            assertThat(service.getReport(reportId)).isSameAs(dto);
            assertThat(service.getLatestReportForOrder(orderId)).isSameAs(dto);
        }
    }

    // ── procedure orders ───────────────────────────────────────────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("GET /procedure-orders/{orderId} and /procedure-orders/patient/{patientId}")
    class ProcedureOrders {
        @Mock private ProcedureOrderRepository procedureOrderRepository;
        @Mock private RoleValidator roleValidator;
        @Mock private RecordAccessPolicy recordAccessPolicy;
        @Mock private CrossHospitalReachRecorder reachRecorder;
        @Spy private PatientSubjectReadGuard subjectReadGuard = realGuard();
        @InjectMocks private ProcedureOrderServiceImpl service;

        private final UUID orderId = UUID.randomUUID();

        @BeforeEach
        void actingAtA() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA.getId());
            when(recordAccessPolicy.readableHospitalIds(any(), any(), any())).thenReturn(Set.of(hospitalA.getId()));
        }

        private void orderAtB(UUID patientId) {
            ProcedureOrder order = ProcedureOrder.builder().patient(patient(patientId)).hospital(hospitalB).build();
            order.setId(orderId);
            when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        }

        @Test
        @DisplayName("her own order at B reads; a stranger's, or hers without the grant, answers as missing")
        void byId() {
            nurseWhoIsAPatient();
            orderAtB(ownPatientId);
            assertThat(service.getProcedureOrder(orderId).getId()).isEqualTo(orderId);

            orderAtB(strangerPatientId);
            assertSameNotFound(() -> service.getProcedureOrder(orderId), () -> {
                when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.empty());
                service.getProcedureOrder(orderId);
            });

            nurseLinkedWithoutTheGrant();
            orderAtB(ownPatientId);
            assertSameNotFound(() -> service.getProcedureOrder(orderId), () -> {
                when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.empty());
                service.getProcedureOrder(orderId);
            });
        }

        @Test
        @DisplayName("a pure patient reads their own order wherever it was written")
        void purePatientOwnElsewhere() {
            login("ROLE_PATIENT");
            orderAtB(ownPatientId);
            assertThat(service.getProcedureOrder(orderId).getId()).isEqualTo(orderId);
        }

        @Test
        @DisplayName("the list: her own at every hospital with no reach; without the grant, held to A")
        void list() {
            nurseWhoIsAPatient();
            service.getProcedureOrdersForPatient(ownPatientId);
            verify(procedureOrderRepository).findByPatient_IdOrderByOrderedAtDesc(ownPatientId);
            verifyNoInteractions(reachRecorder);

            nurseLinkedWithoutTheGrant();
            service.getProcedureOrdersForPatient(ownPatientId);
            verify(procedureOrderRepository).findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(ownPatientId, Set.of(hospitalA.getId()));
        }
    }

    // ── ultrasound ─────────────────────────────────────────────────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("GET /ultrasound/orders/{id}, /orders/patient/{pid}, /reports/{id}, /reports/order/{id}")
    class Ultrasound {
        @Mock private UltrasoundOrderRepository orderRepository;
        @Mock private UltrasoundReportRepository reportRepository;
        @Mock private UltrasoundMapper ultrasoundMapper;
        @Mock private RoleValidator roleValidator;
        @Mock private RecordAccessPolicy recordAccessPolicy;
        @Mock private CrossHospitalReachRecorder reachRecorder;
        @Spy private PatientSubjectReadGuard subjectReadGuard = realGuard();
        @InjectMocks private UltrasoundServiceImpl service;

        private final UUID orderId = UUID.randomUUID();
        private final UUID reportId = UUID.randomUUID();

        @BeforeEach
        void actingAtA() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA.getId());
            when(ultrasoundMapper.toOrderResponseDTO(any())).thenAnswer(inv -> UltrasoundOrderResponseDTO.builder()
                .id(orderId).report(UltrasoundReportResponseDTO.builder().id(reportId).build()).build());
            when(ultrasoundMapper.toReportResponseDTO(any())).thenReturn(UltrasoundReportResponseDTO.builder().id(reportId).build());
        }

        private void rowsAtB(UUID patientId, boolean released) {
            UltrasoundOrder order = new UltrasoundOrder();
            order.setId(orderId);
            order.setPatient(patient(patientId));
            order.setHospital(hospitalB);
            UltrasoundReport report = new UltrasoundReport();
            report.setId(reportId);
            report.setUltrasoundOrder(order);
            report.setHospital(hospitalB);
            report.setReportReviewedByProvider(released);
            report.setPatientNotifiedAt(released ? LocalDateTime.now() : null);
            order.setReport(report);
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(orderRepository.findAllByPatientId(patientId)).thenReturn(List.of(order));
            when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
            when(reportRepository.findByUltrasoundOrderId(orderId)).thenReturn(Optional.of(report));
        }

        private void nothing() {
            when(orderRepository.findById(orderId)).thenReturn(Optional.empty());
            when(reportRepository.findById(reportId)).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("her own order at B reads as the patient copy: an unreleased report is taken off it")
        void ownOrderIsThePatientCopy() {
            nurseWhoIsAPatient();
            rowsAtB(ownPatientId, false);
            assertThat(service.getOrderById(orderId).getReport()).isNull();
            assertThat(service.getOrdersByPatientId(ownPatientId)).singleElement()
                .satisfies(dto -> assertThat(dto.getReport()).isNull());
            verifyNoInteractions(reachRecorder);
        }

        @Test
        @DisplayName("her own released report at B reads; unreleased answers as missing")
        void ownReports() {
            nurseWhoIsAPatient();
            rowsAtB(ownPatientId, true);
            assertThat(service.getReportById(reportId).getId()).isEqualTo(reportId);
            assertThat(service.getReportByOrderId(orderId).getId()).isEqualTo(reportId);

            rowsAtB(ownPatientId, false);
            assertSameNotFound(() -> service.getReportById(reportId), () -> { nothing(); service.getReportById(reportId); });
        }

        @Test
        @DisplayName("a stranger's order at B, or hers without the grant, answers exactly as a missing one")
        void othersAnswerAsMissing() {
            nurseWhoIsAPatient();
            rowsAtB(strangerPatientId, true);
            assertSameNotFound(() -> service.getOrderById(orderId), () -> { nothing(); service.getOrderById(orderId); });

            nurseLinkedWithoutTheGrant();
            rowsAtB(ownPatientId, true);
            assertSameNotFound(() -> service.getOrderById(orderId), () -> { nothing(); service.getOrderById(orderId); });
            rowsAtB(ownPatientId, true);
            assertSameNotFound(() -> service.getReportById(reportId), () -> { nothing(); service.getReportById(reportId); });
        }
    }
}
