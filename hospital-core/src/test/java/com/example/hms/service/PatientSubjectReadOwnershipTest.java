package com.example.hms.service;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.mapper.ImagingOrderMapper;
import com.example.hms.mapper.ImagingReportMapper;
import com.example.hms.mapper.UltrasoundMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.Patient;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.repository.UltrasoundOrderRepository;
import com.example.hms.repository.UltrasoundReportRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.impl.ConsultationServiceImpl;
import com.example.hms.service.impl.ImagingOrderServiceImpl;
import com.example.hms.service.impl.ImagingReportServiceImpl;
import com.example.hms.service.impl.ProcedureOrderServiceImpl;
import com.example.hms.service.impl.UltrasoundServiceImpl;
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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
 * A patient reads only their own rows on every read that takes a patient id,
 * or the id of a row a patient owns, and admits {@code ROLE_PATIENT}.
 *
 * <p>For each read: the patient reads their own; another patient's id answers
 * exactly as an unknown one does — the same exception, key and message on a
 * by-id read, an empty list on a list read — and before any hospital lookup
 * that could answer differently; staff read another patient's rows as before.
 * Every service here runs with the REAL {@link PatientSubjectReadGuard} over a
 * real {@link ControllerAuthUtils}, so the Keycloak case resolves the caller
 * from the {@code appUserId} claim exactly as production does.
 */
@DisplayName("Reads by patient id: a patient reads only their own")
class PatientSubjectReadOwnershipTest {

    private final UUID callerUserId = UUID.randomUUID();
    private final UUID ownPatientId = UUID.randomUUID();
    private final UUID otherPatientId = UUID.randomUUID();
    private final UUID unknownPatientId = UUID.randomUUID();

    /** The guard's own view of the patient table: the caller is linked to one row. */
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

    private void passwordLogin(String... roles) {
        var authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        var principal = new CustomUserDetails(callerUserId, "caller", "pw", true, authorities);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    /** A Keycloak principal: the HMS user id travels in {@code appUserId}, not the subject. */
    private void keycloakLogin(String... roles) {
        var authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        Jwt jwt = Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .claim("sub", "keycloak-subject")
            .claim("appUserId", callerUserId.toString())
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
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

    /** Refusing a real id must be indistinguishable from an unknown one: same type, key, args and message. */
    private static void assertSameNotFound(Executable refused, Executable missing) {
        ResourceNotFoundException a = catchThrowableOfType(refused::execute, ResourceNotFoundException.class);
        ResourceNotFoundException b = catchThrowableOfType(missing::execute, ResourceNotFoundException.class);
        assertThat(a).as("the refused id must answer 404").isNotNull();
        assertThat(b).as("the unknown id must answer 404").isNotNull();
        assertThat(a.getMessageKey()).isEqualTo(b.getMessageKey());
        assertThat(a.getArgs()).isEqualTo(b.getArgs());
        assertThat(a.getMessage()).isEqualTo(b.getMessage());
    }

    // ── GET /consultations/patient/{patientId} ─────────────────────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("GET /consultations/patient/{patientId}")
    class Consultations {
        @Mock private ConsultationRepository consultationRepository;
        @Mock private RoleValidator roleValidator;
        @Spy private PatientSubjectReadGuard subjectReadGuard = realGuard();
        @InjectMocks private ConsultationServiceImpl service;

        @Test
        @DisplayName("a patient reads their own")
        void patientReadsOwn() {
            passwordLogin("ROLE_PATIENT");
            when(consultationRepository.findByPatient_IdOrderByRequestedAtDesc(ownPatientId)).thenReturn(List.of());

            assertThat(service.getConsultationsForPatient(ownPatientId)).isEmpty();
            verify(consultationRepository).findByPatient_IdOrderByRequestedAtDesc(ownPatientId);
        }

        @Test
        @DisplayName("another patient's id answers as an unknown one: empty, before any lookup")
        void foreignAnswersAsUnknown() {
            passwordLogin("ROLE_PATIENT");

            assertThat(service.getConsultationsForPatient(otherPatientId))
                .isEqualTo(service.getConsultationsForPatient(unknownPatientId))
                .isEmpty();
            verifyNoInteractions(consultationRepository, roleValidator);
        }

        @Test
        @DisplayName("over Keycloak the patient is resolved from appUserId: own yes, another's no")
        void keycloakPatient() {
            keycloakLogin("ROLE_PATIENT");

            service.getConsultationsForPatient(ownPatientId);
            verify(consultationRepository).findByPatient_IdOrderByRequestedAtDesc(ownPatientId);
            assertThat(service.getConsultationsForPatient(otherPatientId)).isEmpty();
            verify(consultationRepository, never()).findByPatient_IdOrderByRequestedAtDesc(otherPatientId);
        }

        @Test
        @DisplayName("staff read another patient's, as before — also a nurse who is a patient")
        void staffUnchanged() {
            passwordLogin("ROLE_NURSE", "ROLE_PATIENT");

            service.getConsultationsForPatient(otherPatientId);
            verify(consultationRepository).findByPatient_IdOrderByRequestedAtDesc(otherPatientId);
        }
    }

    // ── GET /imaging/orders/patient/{patientId} ────────────────────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("GET /imaging/orders/patient/{patientId}")
    class ImagingOrders {
        @Mock private ImagingOrderRepository imagingOrderRepository;
        @Mock private ImagingOrderMapper imagingOrderMapper;
        @Mock private RoleValidator roleValidator;
        @Spy private PatientSubjectReadGuard subjectReadGuard = realGuard();
        @InjectMocks private ImagingOrderServiceImpl service;

        @Test
        @DisplayName("a patient reads their own")
        void patientReadsOwn() {
            passwordLogin("ROLE_PATIENT");
            when(imagingOrderRepository.findByPatient_IdOrderByOrderedAtDesc(ownPatientId)).thenReturn(List.of());

            assertThat(service.getOrdersByPatient(ownPatientId, null)).isEmpty();
            verify(imagingOrderRepository).findByPatient_IdOrderByOrderedAtDesc(ownPatientId);
        }

        @Test
        @DisplayName("another patient's id answers as an unknown one: empty, before any lookup")
        void foreignAnswersAsUnknown() {
            passwordLogin("ROLE_PATIENT");

            assertThat(service.getOrdersByPatient(otherPatientId, null))
                .isEqualTo(service.getOrdersByPatient(unknownPatientId, null))
                .isEmpty();
            verifyNoInteractions(imagingOrderRepository, roleValidator);
        }

        @Test
        @DisplayName("staff read another patient's, as before")
        void staffUnchanged() {
            passwordLogin("ROLE_RADIOLOGIST");

            service.getOrdersByPatient(otherPatientId, null);
            verify(imagingOrderRepository).findByPatient_IdOrderByOrderedAtDesc(otherPatientId);
        }
    }

    // ── GET /imaging/results/{reportId} and /imaging/results/order/{orderId} ─

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("GET /imaging/results/{reportId} and /order/{orderId}")
    class ImagingReports {
        @Mock private ImagingReportRepository imagingReportRepository;
        @Mock private ImagingOrderRepository imagingOrderRepository;
        @Mock private ImagingReportMapper imagingReportMapper;
        @Mock private RoleValidator roleValidator;
        @Spy private PatientSubjectReadGuard subjectReadGuard = realGuard();
        @InjectMocks private ImagingReportServiceImpl service;

        private final UUID reportId = UUID.randomUUID();
        private final UUID orderId = UUID.randomUUID();

        private ImagingOrder orderFor(UUID patientId) {
            ImagingOrder order = new ImagingOrder();
            order.setId(orderId);
            order.setPatient(patient(patientId));
            order.setHospital(hospital(UUID.randomUUID()));
            return order;
        }

        private ImagingReport reportFor(UUID patientId) {
            ImagingReport report = new ImagingReport();
            report.setId(reportId);
            report.setImagingOrder(orderFor(patientId));
            report.setHospital(report.getImagingOrder().getHospital());
            return report;
        }

        @Test
        @DisplayName("a patient reads their own report, by id and by order")
        void patientReadsOwn() {
            passwordLogin("ROLE_PATIENT");
            ImagingReport own = reportFor(ownPatientId);
            ImagingReportResponseDTO dto = new ImagingReportResponseDTO();
            when(imagingReportRepository.findById(reportId)).thenReturn(Optional.of(own));
            when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(own.getImagingOrder()));
            when(imagingReportRepository.findFirstByImagingOrder_IdAndLatestVersionIsTrue(orderId))
                .thenReturn(Optional.of(own));
            when(imagingReportMapper.toResponseDTO(own)).thenReturn(dto);

            assertThat(service.getReport(reportId)).isSameAs(dto);
            assertThat(service.getLatestReportForOrder(orderId)).isSameAs(dto);
        }

        @Test
        @DisplayName("another patient's report answers as a missing id, before the hospital check")
        void foreignReportAnswersAsMissing() {
            passwordLogin("ROLE_PATIENT");
            when(imagingReportRepository.findById(reportId)).thenReturn(Optional.of(reportFor(otherPatientId)));
            Executable refused = () -> service.getReport(reportId);
            ResourceNotFoundException first = catchThrowableOfType(refused::execute, ResourceNotFoundException.class);
            when(imagingReportRepository.findById(reportId)).thenReturn(Optional.empty());

            assertSameNotFound(() -> { throw first; }, () -> service.getReport(reportId));
            verifyNoInteractions(roleValidator, imagingReportMapper);
        }

        @Test
        @DisplayName("another patient's order answers as a missing order, before the hospital check")
        void foreignOrderAnswersAsMissing() {
            passwordLogin("ROLE_PATIENT");
            when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(orderFor(otherPatientId)));
            ResourceNotFoundException first = catchThrowableOfType(() -> service.getLatestReportForOrder(orderId), ResourceNotFoundException.class);
            when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.empty());

            assertSameNotFound(() -> { throw first; }, () -> service.getLatestReportForOrder(orderId));
            verifyNoInteractions(roleValidator, imagingReportRepository, imagingReportMapper);
        }

        @Test
        @DisplayName("staff read another patient's report, as before")
        void staffUnchanged() {
            passwordLogin("ROLE_DOCTOR");
            ImagingReport other = reportFor(otherPatientId);
            ImagingReportResponseDTO dto = new ImagingReportResponseDTO();
            when(imagingReportRepository.findById(reportId)).thenReturn(Optional.of(other));
            when(imagingReportMapper.toResponseDTO(other)).thenReturn(dto);

            assertThat(service.getReport(reportId)).isSameAs(dto);
        }
    }

    // ── GET /procedure-orders/{orderId} and /procedure-orders/patient/{patientId} ─

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("GET /procedure-orders/{orderId} and /patient/{patientId}")
    class ProcedureOrders {
        @Mock private ProcedureOrderRepository procedureOrderRepository;
        @Mock private RoleValidator roleValidator;
        @Spy private PatientSubjectReadGuard subjectReadGuard = realGuard();
        @InjectMocks private ProcedureOrderServiceImpl service;

        private final UUID orderId = UUID.randomUUID();

        private ProcedureOrder orderFor(UUID patientId) {
            ProcedureOrder order = new ProcedureOrder();
            order.setId(orderId);
            order.setPatient(patient(patientId));
            return order;
        }

        @Test
        @DisplayName("a patient reads their own order and list")
        void patientReadsOwn() {
            passwordLogin("ROLE_PATIENT");
            when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(orderFor(ownPatientId)));
            when(procedureOrderRepository.findByPatient_IdOrderByOrderedAtDesc(ownPatientId)).thenReturn(List.of());

            assertThat(service.getProcedureOrder(orderId).getId()).isEqualTo(orderId);
            assertThat(service.getProcedureOrdersForPatient(ownPatientId)).isEmpty();
            verify(procedureOrderRepository).findByPatient_IdOrderByOrderedAtDesc(ownPatientId);
        }

        @Test
        @DisplayName("another patient's order answers as a missing id, before the hospital check")
        void foreignOrderAnswersAsMissing() {
            passwordLogin("ROLE_PATIENT");
            when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(orderFor(otherPatientId)));
            ResourceNotFoundException first = catchThrowableOfType(() -> service.getProcedureOrder(orderId), ResourceNotFoundException.class);
            when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.empty());

            assertSameNotFound(() -> { throw first; }, () -> service.getProcedureOrder(orderId));
            verifyNoInteractions(roleValidator);
        }

        @Test
        @DisplayName("another patient's list answers as an unknown id: empty, before any lookup")
        void foreignListAnswersAsUnknown() {
            passwordLogin("ROLE_PATIENT");

            assertThat(service.getProcedureOrdersForPatient(otherPatientId))
                .isEqualTo(service.getProcedureOrdersForPatient(unknownPatientId))
                .isEmpty();
            verifyNoInteractions(procedureOrderRepository, roleValidator);
        }

        @Test
        @DisplayName("staff read another patient's, as before")
        void staffUnchanged() {
            passwordLogin("ROLE_NURSE");
            when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(orderFor(otherPatientId)));

            assertThat(service.getProcedureOrder(orderId).getId()).isEqualTo(orderId);
            service.getProcedureOrdersForPatient(otherPatientId);
            verify(procedureOrderRepository).findByPatient_IdOrderByOrderedAtDesc(otherPatientId);
        }
    }

    // ── GET /ultrasound/orders/..., /ultrasound/reports/... ─────────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("GET /ultrasound/orders/{orderId}, /orders/patient/{patientId}, /reports/{reportId}, /reports/order/{orderId}")
    class Ultrasound {
        @Mock private UltrasoundOrderRepository orderRepository;
        @Mock private UltrasoundReportRepository reportRepository;
        @Mock private UltrasoundMapper ultrasoundMapper;
        @Spy private PatientSubjectReadGuard subjectReadGuard = realGuard();
        @InjectMocks private UltrasoundServiceImpl service;

        private final UUID orderId = UUID.randomUUID();
        private final UUID reportId = UUID.randomUUID();

        private UltrasoundOrder orderFor(UUID patientId) {
            UltrasoundOrder order = new UltrasoundOrder();
            order.setId(orderId);
            order.setPatient(patient(patientId));
            return order;
        }

        private UltrasoundReport reportFor(UUID patientId) {
            UltrasoundReport report = new UltrasoundReport();
            report.setId(reportId);
            report.setUltrasoundOrder(orderFor(patientId));
            return report;
        }

        private void stubRows(UUID patientId) {
            UltrasoundOrder order = orderFor(patientId);
            UltrasoundReport report = reportFor(patientId);
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
            when(reportRepository.findByUltrasoundOrderId(orderId)).thenReturn(Optional.of(report));
            when(ultrasoundMapper.toOrderResponseDTO(any())).thenReturn(UltrasoundOrderResponseDTO.builder().id(orderId).build());
            when(ultrasoundMapper.toReportResponseDTO(any())).thenReturn(UltrasoundReportResponseDTO.builder().id(reportId).build());
        }

        private void stubNoRows() {
            when(orderRepository.findById(orderId)).thenReturn(Optional.empty());
            when(reportRepository.findById(reportId)).thenReturn(Optional.empty());
            when(reportRepository.findByUltrasoundOrderId(orderId)).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("a patient reads their own order, list, report and report by order")
        void patientReadsOwn() {
            passwordLogin("ROLE_PATIENT");
            stubRows(ownPatientId);

            assertThat(service.getOrderById(orderId).getId()).isEqualTo(orderId);
            assertThat(service.getReportById(reportId).getId()).isEqualTo(reportId);
            assertThat(service.getReportByOrderId(orderId).getId()).isEqualTo(reportId);
            service.getOrdersByPatientId(ownPatientId);
            verify(orderRepository).findAllByPatientId(ownPatientId);
        }

        @Test
        @DisplayName("another patient's order, report and report-by-order answer as missing ids")
        void foreignByIdAnswersAsMissing() {
            passwordLogin("ROLE_PATIENT");
            stubRows(otherPatientId);
            ResourceNotFoundException order = catchThrowableOfType(() -> service.getOrderById(orderId), ResourceNotFoundException.class);
            ResourceNotFoundException report = catchThrowableOfType(() -> service.getReportById(reportId), ResourceNotFoundException.class);
            ResourceNotFoundException byOrder = catchThrowableOfType(() -> service.getReportByOrderId(orderId), ResourceNotFoundException.class);
            stubNoRows();

            assertSameNotFound(() -> { throw order; }, () -> service.getOrderById(orderId));
            assertSameNotFound(() -> { throw report; }, () -> service.getReportById(reportId));
            assertSameNotFound(() -> { throw byOrder; }, () -> service.getReportByOrderId(orderId));
        }

        @Test
        @DisplayName("another patient's list answers as an unknown id: empty, with or without a status")
        void foreignListAnswersAsUnknown() {
            keycloakLogin("ROLE_PATIENT");

            assertThat(service.getOrdersByPatientId(otherPatientId))
                .isEqualTo(service.getOrdersByPatientId(unknownPatientId))
                .isEmpty();
            assertThat(service.getOrdersByPatientIdAndStatus(otherPatientId,
                com.example.hms.enums.UltrasoundOrderStatus.ORDERED)).isEmpty();
            verifyNoInteractions(orderRepository);
        }

        @Test
        @DisplayName("staff read another patient's, as before")
        void staffUnchanged() {
            passwordLogin("ROLE_MIDWIFE");
            stubRows(otherPatientId);

            assertThat(service.getOrderById(orderId).getId()).isEqualTo(orderId);
            assertThat(service.getReportById(reportId).getId()).isEqualTo(reportId);
            assertThat(service.getReportByOrderId(orderId).getId()).isEqualTo(reportId);
            service.getOrdersByPatientId(otherPatientId);
            verify(orderRepository).findAllByPatientId(otherPatientId);
        }
    }

    // ── GET /appointments/{id}, /patients/{patientId}, /patients/username/{u} ─

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("GET /appointments/{id}, /patients/{patientId}, /patients/username/{patientUsername}")
    class Appointments {
        @Mock private AppointmentRepository appointmentRepository;
        @Mock private PatientRepository patientRepository;
        @Mock private UserRepository userRepository;
        @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
        @Mock private AppointmentMapper appointmentMapper;
        @Spy private PatientSubjectReadGuard subjectReadGuard = realGuard();
        @InjectMocks private AppointmentServiceImpl service;

        private static final String CALLER = "patient001";
        private final UUID appointmentId = UUID.randomUUID();
        /** The hospital that registered the caller: they hold a ROLE_PATIENT assignment there. */
        private final Hospital registeredAt = hospital(UUID.randomUUID());
        private User caller;

        @BeforeEach
        void theCallerIsRegisteredAtAHospital() {
            caller = new User();
            caller.setId(callerUserId);
            caller.setUsername(CALLER);
            when(userRepository.findByUsername(CALLER)).thenReturn(Optional.of(caller));
            UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
            assignment.setActive(true);
            assignment.setHospital(registeredAt);
            when(assignmentRepository.findAllByUserId(callerUserId)).thenReturn(List.of(assignment));
            when(appointmentMapper.toAppointmentResponseDTO(any())).thenReturn(new AppointmentResponseDTO());
        }

        private Patient patientRow(UUID id, User user) {
            Patient p = patient(id);
            p.setUser(user);
            return p;
        }

        private Appointment appointmentOf(Patient subject) {
            Appointment a = new Appointment();
            a.setId(appointmentId);
            a.setPatient(subject);
            a.setHospital(registeredAt);
            return a;
        }

        private User otherUser() {
            User u = new User();
            u.setId(UUID.randomUUID());
            u.setUsername("patient002");
            return u;
        }

        @Test
        @DisplayName("a patient reads their own appointment and list")
        void patientReadsOwn() {
            passwordLogin("ROLE_PATIENT");
            Patient own = patientRow(ownPatientId, caller);
            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointmentOf(own)));
            when(patientRepository.findById(ownPatientId)).thenReturn(Optional.of(own));
            when(appointmentRepository.findByPatient_Id(ownPatientId)).thenReturn(List.of(appointmentOf(own)));

            assertThat(service.getAppointmentById(appointmentId, Locale.ENGLISH, CALLER)).isNotNull();
            assertThat(service.getAppointmentsByPatientId(ownPatientId, Locale.ENGLISH, CALLER)).hasSize(1);
        }

        @Test
        @DisplayName("another patient's appointment at the caller's own hospital answers as a missing id")
        void foreignAppointmentAnswersAsMissing() {
            passwordLogin("ROLE_PATIENT");
            // Same hospital as the caller's registration: hospital scope alone let this through.
            when(appointmentRepository.findById(appointmentId))
                .thenReturn(Optional.of(appointmentOf(patientRow(otherPatientId, otherUser()))));
            ResourceNotFoundException first = catchThrowableOfType(() -> service.getAppointmentById(appointmentId, Locale.ENGLISH, CALLER), ResourceNotFoundException.class);
            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.empty());

            assertSameNotFound(() -> { throw first; },
                () -> service.getAppointmentById(appointmentId, Locale.ENGLISH, CALLER));
            verify(appointmentMapper, never()).toAppointmentResponseDTO(any());
        }

        @Test
        @DisplayName("another patient's list at the caller's own hospital answers as an unknown patient id")
        void foreignListAnswersAsUnknown() {
            passwordLogin("ROLE_PATIENT");
            Patient other = patientRow(otherPatientId, otherUser());
            when(patientRepository.findById(otherPatientId)).thenReturn(Optional.of(other));
            when(appointmentRepository.findByPatient_Id(otherPatientId)).thenReturn(List.of(appointmentOf(other)));
            when(patientRepository.findById(unknownPatientId)).thenReturn(Optional.empty());

            ResourceNotFoundException refused = catchThrowableOfType(() -> service.getAppointmentsByPatientId(otherPatientId, Locale.ENGLISH, CALLER), ResourceNotFoundException.class);
            assertThat(refused).isNotNull();
            // The unknown id is refused by the same guard; compare with what the
            // lookup itself answers for a row that does not exist, as staff see it.
            passwordLogin("ROLE_RECEPTIONIST");
            ResourceNotFoundException missing = catchThrowableOfType(() -> service.getAppointmentsByPatientId(unknownPatientId, Locale.ENGLISH, CALLER), ResourceNotFoundException.class);
            assertSameNotFound(() -> { throw refused; }, () -> { throw missing; });
            verify(appointmentRepository, never()).findByPatient_Id(otherPatientId);
        }

        @Test
        @DisplayName("another patient's username answers as an unknown username, before any lookup")
        void foreignUsernameAnswersAsUnknown() {
            passwordLogin("ROLE_PATIENT");
            User other = otherUser();
            when(userRepository.findByUsername("patient002")).thenReturn(Optional.of(other));
            when(patientRepository.findByUserId(other.getId()))
                .thenReturn(Optional.of(patientRow(otherPatientId, other)));
            ResourceNotFoundException refused = catchThrowableOfType(() -> service.getAppointmentsByPatientUsername("patient002", Locale.ENGLISH, CALLER), ResourceNotFoundException.class);
            when(userRepository.findByUsername("patient002")).thenReturn(Optional.empty());
            passwordLogin("ROLE_RECEPTIONIST");

            assertSameNotFound(() -> { throw refused; },
                () -> service.getAppointmentsByPatientUsername("patient002", Locale.ENGLISH, CALLER));
            verify(patientRepository, never()).findByUserId(other.getId());
        }

        @Test
        @DisplayName("a patient names themselves by username and reads their own")
        void ownUsername() {
            passwordLogin("ROLE_PATIENT");
            Patient own = patientRow(ownPatientId, caller);
            when(patientRepository.findByUserId(callerUserId)).thenReturn(Optional.of(own));
            when(patientRepository.findById(ownPatientId)).thenReturn(Optional.of(own));
            when(appointmentRepository.findByPatient_Id(ownPatientId)).thenReturn(List.of(appointmentOf(own)));

            assertThat(service.getAppointmentsByPatientUsername(CALLER, Locale.ENGLISH, CALLER)).hasSize(1);
        }

        @Test
        @DisplayName("front-desk staff at the hospital read another patient's appointments, as before")
        void staffUnchanged() {
            passwordLogin("ROLE_RECEPTIONIST");
            Patient other = patientRow(otherPatientId, otherUser());
            when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointmentOf(other)));
            when(patientRepository.findById(otherPatientId)).thenReturn(Optional.of(other));
            when(appointmentRepository.findByPatient_Id(otherPatientId)).thenReturn(List.of(appointmentOf(other)));

            assertThat(service.getAppointmentById(appointmentId, Locale.ENGLISH, CALLER)).isNotNull();
            assertThat(service.getAppointmentsByPatientId(otherPatientId, Locale.ENGLISH, CALLER)).hasSize(1);
        }
    }
}
