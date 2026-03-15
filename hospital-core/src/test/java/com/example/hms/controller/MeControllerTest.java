package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.DashboardConfigResponseDTO;
import com.example.hms.payload.dto.StaffResponseDTO;
import com.example.hms.payload.dto.clinical.ClinicalAlertDTO;
import com.example.hms.payload.dto.clinical.ClinicalDashboardResponseDTO;
import com.example.hms.payload.dto.clinical.ClinicalInboxItemDTO;
import com.example.hms.payload.dto.clinical.CriticalStripDTO;
import com.example.hms.payload.dto.clinical.DashboardKPI;
import com.example.hms.payload.dto.clinical.DoctorResultQueueItemDTO;
import com.example.hms.payload.dto.clinical.DoctorWorklistItemDTO;
import com.example.hms.payload.dto.clinical.InboxCountsDTO;
import com.example.hms.payload.dto.clinical.OnCallStatusDTO;
import com.example.hms.payload.dto.clinical.PatientFlowItemDTO;
import com.example.hms.payload.dto.clinical.PatientSnapshotDTO;
import com.example.hms.payload.dto.clinical.RoomedPatientDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.ClinicalDashboardService;
import com.example.hms.service.DashboardConfigService;
import com.example.hms.service.DoctorWorklistService;
import com.example.hms.service.PatientFlowService;
import com.example.hms.service.PatientSnapshotService;
import com.example.hms.service.ResultReviewService;
import com.example.hms.service.StaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for MeController - Phase 1 Day 2
 * Tests all 6 new endpoints for clinical dashboard functionality
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S100") // allow descriptive test method names with underscores
class MeControllerTest {

    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_MIDWIFE = "ROLE_MIDWIFE";
    private static final String ROLE_RECEPTIONIST = "ROLE_RECEPTIONIST";
    private static final String TEST_TOKEN_VALUE = "test-token";
    private static final String UNKNOWN_USER_IDENTIFIER = "unknown.user@hospital.com";
    private static final String PATIENT_NAME_JOHN_DOE = "John Doe";
    private static final String USERNAME_JOHN_DOE = "john.doe";
    private static final String HOSPITAL_NAME_TEST = "Test Hospital";
    private static final String SEVERITY_CRITICAL = "CRITICAL";

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRoleHospitalAssignmentRepository assignmentRepository;

    @Mock
    private ClinicalDashboardService clinicalDashboardService;

    @Mock
    private StaffService staffService;

    @Mock
    private DashboardConfigService dashboardConfigService;

    @Mock
    private DoctorWorklistService doctorWorklistService;

    @Mock
    private PatientFlowService patientFlowService;

    @Mock
    private ResultReviewService resultReviewService;

    @Mock
    private PatientSnapshotService patientSnapshotService;

    private MeController controller;

    private UUID testUserId;
    private UUID testHospitalId;
    private JwtAuthenticationToken doctorAuth;
        private JwtAuthenticationToken midwifeAuth;

    @BeforeEach
    void setUp() {
        controller = new MeController(hospitalRepository, userRepository, assignmentRepository,
                clinicalDashboardService, staffService, dashboardConfigService,
                doctorWorklistService, patientFlowService, resultReviewService, patientSnapshotService);

        testUserId = UUID.randomUUID();
        testHospitalId = UUID.randomUUID();

        // Create JWT auth for doctor role
        Jwt jwt = Jwt.withTokenValue(TEST_TOKEN_VALUE)
                .header("alg", "none")
                .claim("sub", testUserId.toString())
                .build();
        doctorAuth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(ROLE_DOCTOR)));

        Jwt midwifeJwt = Jwt.withTokenValue(TEST_TOKEN_VALUE + "-midwife")
                .header("alg", "none")
                .claim("sub", testUserId.toString())
                .build();
        midwifeAuth = new JwtAuthenticationToken(midwifeJwt, List.of(new SimpleGrantedAuthority(ROLE_MIDWIFE)));
    }

    private static <T> T requireBody(ResponseEntity<T> response) {
        assertNotNull(response);
        T body = response.getBody();
        assertNotNull(body);
        return body;
    }

    // ========== GET /api/me/clinical-dashboard ==========

    @Test
    void getClinicalDashboard_shouldReturnDashboardData() {
        // Arrange
        ClinicalDashboardResponseDTO expectedDashboard = ClinicalDashboardResponseDTO.builder()
                .kpis(List.of(
                        DashboardKPI.builder()
                                .label("Active Patients")
                                .value(12)
                                .build()))
                .alerts(List.of())
                .inboxCounts(InboxCountsDTO.builder()
                        .unreadMessages(5)
                        .pendingRefills(3)
                        .build())
                .build();

        when(clinicalDashboardService.getClinicalDashboard(testUserId)).thenReturn(expectedDashboard);

        // Act
        ResponseEntity<ApiResponseWrapper<ClinicalDashboardResponseDTO>> response = controller
                .getClinicalDashboard(doctorAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<ClinicalDashboardResponseDTO> body = requireBody(response);
        assertEquals(expectedDashboard, body.getData());
        verify(clinicalDashboardService).getClinicalDashboard(testUserId);
    }

    @Test
    void getClinicalDashboard_withInvalidAuth_shouldThrowException() {
        // Arrange - Authentication with username but user not found in database
        Authentication invalidAuth = mock(Authentication.class);
        when(invalidAuth.getName()).thenReturn(UNKNOWN_USER_IDENTIFIER);

        // Mock repository to return empty (user not found)
        when(userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
                UNKNOWN_USER_IDENTIFIER,
                UNKNOWN_USER_IDENTIFIER,
                UNKNOWN_USER_IDENTIFIER))
                .thenReturn(Optional.empty());

        // Act & Assert - Should throw BusinessException when user cannot be resolved
        assertThrows(BusinessException.class, () -> controller.getClinicalDashboard(invalidAuth));

        // Verify the repository was called
        verify(userRepository).findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
                UNKNOWN_USER_IDENTIFIER,
                UNKNOWN_USER_IDENTIFIER,
                UNKNOWN_USER_IDENTIFIER);
    }

    // ========== GET /api/me/critical-alerts ==========

    @Test
    void getCriticalAlerts_withDefaultHours_shouldReturn24HourAlerts() {
        // Arrange
        List<ClinicalAlertDTO> expectedAlerts = List.of(
                ClinicalAlertDTO.builder()
                        .id(UUID.randomUUID())
                        .severity(SEVERITY_CRITICAL)
                        .type("LAB_CRITICAL")
                        .title("Critical Potassium Level")
                        .message("K+ 6.5 mmol/L")
                        .patientName(PATIENT_NAME_JOHN_DOE)
                        .timestamp(LocalDateTime.now())
                        .acknowledged(false)
                        .build());

        when(clinicalDashboardService.getCriticalAlerts(testUserId, 24)).thenReturn(expectedAlerts);

        // Act
        ResponseEntity<ApiResponseWrapper<List<ClinicalAlertDTO>>> response = controller.getCriticalAlerts(24,
                doctorAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<List<ClinicalAlertDTO>> body = requireBody(response);
        assertEquals(1, body.getData().size());
        assertEquals(SEVERITY_CRITICAL, body.getData().get(0).getSeverity());
        verify(clinicalDashboardService).getCriticalAlerts(testUserId, 24);
    }

    @Test
    void getCriticalAlerts_withCustomHours_shouldReturnAlertsForSpecifiedWindow() {
        // Arrange
        int customHours = 72;
        List<ClinicalAlertDTO> expectedAlerts = List.of(
                ClinicalAlertDTO.builder()
                        .id(UUID.randomUUID())
                        .severity("URGENT")
                        .type("VITAL_ABNORMAL")
                        .title("Abnormal BP")
                        .build());

        when(clinicalDashboardService.getCriticalAlerts(testUserId, customHours)).thenReturn(expectedAlerts);

        // Act
        ResponseEntity<ApiResponseWrapper<List<ClinicalAlertDTO>>> response = controller.getCriticalAlerts(customHours,
                doctorAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<List<ClinicalAlertDTO>> body = requireBody(response);
        assertEquals(1, body.getData().size());
        verify(clinicalDashboardService).getCriticalAlerts(testUserId, customHours);
    }

    @Test
    void getCriticalAlerts_withNoAlerts_shouldReturnEmptyList() {
        // Arrange
        when(clinicalDashboardService.getCriticalAlerts(testUserId, 24)).thenReturn(List.of());

        // Act
        ResponseEntity<ApiResponseWrapper<List<ClinicalAlertDTO>>> response = controller.getCriticalAlerts(24,
                doctorAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<List<ClinicalAlertDTO>> body = requireBody(response);
        assertTrue(body.getData().isEmpty());
    }

    @Test
    void getCriticalAlerts_asMidwife_shouldReturnAlerts() {
        List<ClinicalAlertDTO> expected = List.of(
                ClinicalAlertDTO.builder()
                        .id(UUID.randomUUID())
                        .severity(SEVERITY_CRITICAL)
                        .title("Fetal distress alert")
                        .build());

        when(clinicalDashboardService.getCriticalAlerts(testUserId, 24)).thenReturn(expected);

        ResponseEntity<ApiResponseWrapper<List<ClinicalAlertDTO>>> response = controller.getCriticalAlerts(24,
                midwifeAuth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<List<ClinicalAlertDTO>> body = requireBody(response);
        assertEquals(1, body.getData().size());
        verify(clinicalDashboardService).getCriticalAlerts(testUserId, 24);
    }

    // ========== POST /api/me/alerts/{alertId}/acknowledge ==========

    @Test
    void acknowledgeAlert_shouldCallServiceAndReturnOk() {
        // Arrange
        UUID alertId = UUID.randomUUID();
        doNothing().when(clinicalDashboardService).acknowledgeAlert(alertId, testUserId);

        // Act
        ResponseEntity<Void> response = controller.acknowledgeAlert(alertId, doctorAuth);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(clinicalDashboardService).acknowledgeAlert(alertId, testUserId);
    }

    @Test
    void acknowledgeAlert_withMultipleAlerts_shouldAcknowledgeEach() {
        // Arrange
        UUID alertId1 = UUID.randomUUID();
        UUID alertId2 = UUID.randomUUID();

        doNothing().when(clinicalDashboardService).acknowledgeAlert(any(UUID.class), eq(testUserId));

        // Act
        controller.acknowledgeAlert(alertId1, doctorAuth);
        controller.acknowledgeAlert(alertId2, doctorAuth);

        // Assert
        verify(clinicalDashboardService).acknowledgeAlert(alertId1, testUserId);
        verify(clinicalDashboardService).acknowledgeAlert(alertId2, testUserId);
        verify(clinicalDashboardService, times(2)).acknowledgeAlert(any(UUID.class), eq(testUserId));
    }

    // ========== GET /api/me/inbox-counts ==========

    @Test
    void getInboxCounts_shouldReturnAllCounts() {
        // Arrange
        InboxCountsDTO expectedCounts = InboxCountsDTO.builder()
                .unreadMessages(5)
                .pendingRefills(3)
                .pendingResults(2)
                .tasksToComplete(7)
                .documentsToSign(1)
                .build();

        when(clinicalDashboardService.getInboxCounts(testUserId)).thenReturn(expectedCounts);

        // Act
        ResponseEntity<ApiResponseWrapper<InboxCountsDTO>> response = controller.getInboxCounts(doctorAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<InboxCountsDTO> body = requireBody(response);
        InboxCountsDTO counts = body.getData();
        assertEquals(5, counts.getUnreadMessages());
        assertEquals(3, counts.getPendingRefills());
        assertEquals(2, counts.getPendingResults());
        assertEquals(7, counts.getTasksToComplete());
        assertEquals(1, counts.getDocumentsToSign());
        verify(clinicalDashboardService).getInboxCounts(testUserId);
    }

    @Test
    void getInboxCounts_withZeroCounts_shouldReturnZeros() {
        // Arrange
        InboxCountsDTO zeroCounts = InboxCountsDTO.builder()
                .unreadMessages(0)
                .pendingRefills(0)
                .pendingResults(0)
                .tasksToComplete(0)
                .documentsToSign(0)
                .build();

        when(clinicalDashboardService.getInboxCounts(testUserId)).thenReturn(zeroCounts);

        // Act
        ResponseEntity<ApiResponseWrapper<InboxCountsDTO>> response = controller.getInboxCounts(doctorAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<InboxCountsDTO> body = requireBody(response);
        InboxCountsDTO counts = body.getData();
        assertEquals(0, counts.getUnreadMessages());
        assertEquals(0, counts.getPendingRefills());
    }

    // ========== GET /api/me/roomed-patients ==========

    @Test
    void getRoomedPatients_shouldReturnPatientList() {
        // Arrange
        List<RoomedPatientDTO> expectedPatients = List.of(
                RoomedPatientDTO.builder()
                        .id(UUID.randomUUID())
                        .patientName(PATIENT_NAME_JOHN_DOE)
                        .room("Room 101")
                        .chiefComplaint("Chest pain")
                        .waitTimeMinutes(45)
                        .build(),
                RoomedPatientDTO.builder()
                        .id(UUID.randomUUID())
                        .patientName("Jane Smith")
                        .room("Room 102")
                        .chiefComplaint("Headache")
                        .waitTimeMinutes(30)
                        .build());

        when(clinicalDashboardService.getRoomedPatients(testUserId)).thenReturn(expectedPatients);

        // Act
        ResponseEntity<ApiResponseWrapper<List<RoomedPatientDTO>>> response = controller.getRoomedPatients(doctorAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<List<RoomedPatientDTO>> body = requireBody(response);
        List<RoomedPatientDTO> patients = body.getData();
        assertEquals(2, patients.size());
        assertEquals(PATIENT_NAME_JOHN_DOE, patients.get(0).getPatientName());
        assertEquals("Room 101", patients.get(0).getRoom());
        assertEquals(45, patients.get(0).getWaitTimeMinutes());
        verify(clinicalDashboardService).getRoomedPatients(testUserId);
    }

    @Test
    void getRoomedPatients_withNoPatients_shouldReturnEmptyList() {
        // Arrange
        when(clinicalDashboardService.getRoomedPatients(testUserId)).thenReturn(List.of());

        // Act
        ResponseEntity<ApiResponseWrapper<List<RoomedPatientDTO>>> response = controller.getRoomedPatients(doctorAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<List<RoomedPatientDTO>> body = requireBody(response);
        assertTrue(body.getData().isEmpty());
    }

    // ========== GET /api/me/on-call-status ==========

    @Test
    void getOnCallStatus_shouldReturnStatus() {
        // Arrange
        OnCallStatusDTO expectedStatus = OnCallStatusDTO.builder()
                .isOnCall(true)
                .shiftStart(LocalDateTime.of(LocalDate.now(), LocalTime.of(8, 0)))
                .shiftEnd(LocalDateTime.of(LocalDate.now(), LocalTime.of(20, 0)))
                .coveringFor(List.of("Emergency"))
                .backupProvider("Dr. Brown")
                .build();

        when(clinicalDashboardService.getOnCallStatus(testUserId)).thenReturn(expectedStatus);

        // Act
        ResponseEntity<ApiResponseWrapper<OnCallStatusDTO>> response = controller.getOnCallStatus(doctorAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<OnCallStatusDTO> body = requireBody(response);
        OnCallStatusDTO status = body.getData();
        assertEquals(true, status.getIsOnCall());
        assertEquals(List.of("Emergency"), status.getCoveringFor());
        assertEquals("Dr. Brown", status.getBackupProvider());
        verify(clinicalDashboardService).getOnCallStatus(testUserId);
    }

    @Test
    void getOnCallStatus_whenNotOnCall_shouldReturnFalse() {
        // Arrange
        OnCallStatusDTO notOnCallStatus = OnCallStatusDTO.builder()
                .isOnCall(false)
                .build();

        when(clinicalDashboardService.getOnCallStatus(testUserId)).thenReturn(notOnCallStatus);

        // Act
        ResponseEntity<ApiResponseWrapper<OnCallStatusDTO>> response = controller.getOnCallStatus(doctorAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<OnCallStatusDTO> body = requireBody(response);
        assertEquals(false, body.getData().getIsOnCall());
    }

    @Test
    void getActiveStaff_shouldReturnActiveStaffForCurrentUser() {
        StaffResponseDTO staff = StaffResponseDTO.builder()
                .id(UUID.randomUUID().toString())
                .name("Dr. Example")
                .active(true)
                .build();

        when(staffService.getActiveStaffByUserId(eq(testUserId), any(Locale.class)))
                .thenReturn(List.of(staff));

        ResponseEntity<List<StaffResponseDTO>> response = controller.getActiveStaff(doctorAuth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<StaffResponseDTO> staffList = requireBody(response);
        assertEquals(1, staffList.size());
        assertEquals("Dr. Example", staffList.get(0).getName());
        verify(staffService).getActiveStaffByUserId(eq(testUserId), any(Locale.class));
    }

    @Test
    void getDashboardConfig_shouldReturnConfigForCurrentUser() {
        DashboardConfigResponseDTO config = DashboardConfigResponseDTO.builder()
                .userId(testUserId)
                .primaryRoleCode(ROLE_DOCTOR)
                .mergedPermissions(List.of("patients:view"))
                .build();

        when(dashboardConfigService.getDashboardConfig(testUserId)).thenReturn(config);

        ResponseEntity<DashboardConfigResponseDTO> response = controller.getDashboardConfig(doctorAuth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        DashboardConfigResponseDTO body = requireBody(response);
        assertEquals(ROLE_DOCTOR, body.getPrimaryRoleCode());
        verify(dashboardConfigService).getDashboardConfig(testUserId);
    }

    @Test
    void getDashboardConfig_asMidwife_shouldExposeExpectedPermissions() {
        List<String> expectedPermissions = List.of(
                "View Patient Records",
                "Update Patient Records",
                "Monitor Labor Progress",
                "Document Delivery Notes",
                "Perform Prenatal Assessments",
                "Create Birth Plans",
                "Administer Medications",
                "Update Vital Signs",
                "Perform Postpartum Care",
                "Provide Breastfeeding Support",
                "Schedule Prenatal Appointments",
                "Document Newborn Assessment",
                "Order Lab Tests",
                "View Lab Results",
                "Create Referrals to OB-GYN",
                "Educate Patients",
                "Manage High-Risk Pregnancies",
                "Perform Ultrasound Scans",
                "Document Maternal History",
                "Alert Obstetricians");

        DashboardConfigResponseDTO.RoleAssignmentConfigDTO midwifeAssignment =
                DashboardConfigResponseDTO.RoleAssignmentConfigDTO.builder()
                        .roleCode(ROLE_MIDWIFE)
                        .permissions(expectedPermissions)
                        .build();

        DashboardConfigResponseDTO config = DashboardConfigResponseDTO.builder()
                .userId(testUserId)
                .primaryRoleCode(ROLE_MIDWIFE)
                .roles(List.of(midwifeAssignment))
                .mergedPermissions(expectedPermissions)
                .build();

        when(dashboardConfigService.getDashboardConfig(testUserId)).thenReturn(config);

        ResponseEntity<DashboardConfigResponseDTO> response = controller.getDashboardConfig(midwifeAuth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        DashboardConfigResponseDTO body = requireBody(response);
        assertEquals(ROLE_MIDWIFE, body.getPrimaryRoleCode());
        assertEquals(expectedPermissions, body.getMergedPermissions());
        verify(dashboardConfigService).getDashboardConfig(testUserId);
    }

    // ========== User ID Resolution Tests ==========

    @Test
    void resolveUserId_fromJwtSub_shouldExtractUserId() {
        // Arrange - already set up in @BeforeEach with testUserId in JWT

        // Create a service call that will trigger resolveUserId
        when(clinicalDashboardService.getOnCallStatus(testUserId))
                .thenReturn(OnCallStatusDTO.builder().isOnCall(false).build());

        // Act
        controller.getOnCallStatus(doctorAuth);

        // Assert
        verify(clinicalDashboardService).getOnCallStatus(testUserId);
    }

    @Test
    void resolveUserId_fromUserRepository_shouldFindUser() {
        // Arrange
        Jwt jwtWithPrincipal = Jwt.withTokenValue(TEST_TOKEN_VALUE)
                .header("alg", "none")
                .claim("sub", USERNAME_JOHN_DOE) 
                .build();
        JwtAuthenticationToken authWithUsername = new JwtAuthenticationToken(
                jwtWithPrincipal,
                List.of(new SimpleGrantedAuthority(ROLE_DOCTOR)));

        User mockUser = new User();
        mockUser.setId(testUserId);
        when(userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
                USERNAME_JOHN_DOE, USERNAME_JOHN_DOE, USERNAME_JOHN_DOE))
                .thenReturn(Optional.of(mockUser));

        when(clinicalDashboardService.getOnCallStatus(testUserId))
                .thenReturn(OnCallStatusDTO.builder().isOnCall(false).build());

        // Act
        controller.getOnCallStatus(authWithUsername);

        // Assert
        verify(userRepository).findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
                USERNAME_JOHN_DOE, USERNAME_JOHN_DOE, USERNAME_JOHN_DOE);
        verify(clinicalDashboardService).getOnCallStatus(testUserId);
    }

    // ========== GET /api/me/hospital (existing endpoint test) ==========

    @Test
    void myHospital_withValidHospitalId_shouldReturnHospital() {
        // Arrange
        Hospital hospital = new Hospital();
        hospital.setId(testHospitalId);
        hospital.setName(HOSPITAL_NAME_TEST);

        Jwt jwt = Jwt.withTokenValue(TEST_TOKEN_VALUE)
                .header("alg", "none")
                .claim("sub", testUserId.toString())
                .claim("hospitalId", testHospitalId.toString())
                .build();
        JwtAuthenticationToken receptionistAuth = new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority(ROLE_RECEPTIONIST)));

        when(hospitalRepository.findById(testHospitalId)).thenReturn(Optional.of(hospital));

        // Act
        ResponseEntity<MeController.HospitalMinimalDTO> response = controller.myHospital(receptionistAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        MeController.HospitalMinimalDTO body = requireBody(response);
        assertEquals(testHospitalId, body.id());
        assertEquals(HOSPITAL_NAME_TEST, body.name());
    }

    @Test
    void myHospital_withoutHospitalIdInJwt_shouldFallbackToAssignment() {
        // Arrange
        Hospital hospital = new Hospital();
        hospital.setId(testHospitalId);
        hospital.setName(HOSPITAL_NAME_TEST);

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setHospital(hospital);
        assignment.setActive(true);

        Jwt jwt = Jwt.withTokenValue(TEST_TOKEN_VALUE)
                .header("alg", "none")
                .claim("sub", testUserId.toString())
                .build();
        JwtAuthenticationToken receptionistAuth = new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority(ROLE_RECEPTIONIST)));

        when(assignmentRepository.findAllDetailedByUserId(testUserId))
                .thenReturn(List.of(assignment));
        when(hospitalRepository.findById(testHospitalId)).thenReturn(Optional.of(hospital));

        // Act
        ResponseEntity<MeController.HospitalMinimalDTO> response = controller.myHospital(receptionistAuth);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        MeController.HospitalMinimalDTO body = requireBody(response);
        assertEquals(testHospitalId, body.id());
        verify(assignmentRepository).findAllDetailedByUserId(testUserId);
    }

    // ========== GET /api/me/critical-strip ==========

    @Test
    void getCriticalStrip_shouldReturnStripData() {
        CriticalStripDTO strip = CriticalStripDTO.builder()
                .criticalLabsCount(2)
                .waitingLongCount(1)
                .pendingConsultsCount(3)
                .unsignedNotesCount(4)
                .pendingOrderReviewCount(5)
                .build();
        when(doctorWorklistService.getCriticalStrip(testUserId)).thenReturn(strip);

        ResponseEntity<ApiResponseWrapper<CriticalStripDTO>> response = controller.getCriticalStrip(doctorAuth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponseWrapper<CriticalStripDTO> body = requireBody(response);
        assertEquals(2, body.getData().getCriticalLabsCount());
        assertEquals(3, body.getData().getPendingConsultsCount());
        verify(doctorWorklistService).getCriticalStrip(testUserId);
    }

    // ========== GET /api/me/worklist ==========

    @Test
    void getWorklist_withoutFilters_shouldReturnAllItems() {
        List<DoctorWorklistItemDTO> items = List.of(
                DoctorWorklistItemDTO.builder().patientName("Pat A").encounterStatus("IN_PROGRESS").build());
        when(doctorWorklistService.getWorklist(testUserId, null, null, null)).thenReturn(items);

        ResponseEntity<ApiResponseWrapper<List<DoctorWorklistItemDTO>>> response =
                controller.getWorklist(null, null, null, doctorAuth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, requireBody(response).getData().size());
        verify(doctorWorklistService).getWorklist(testUserId, null, null, null);
    }

    @Test
    void getWorklist_withStatusAndUrgency_shouldPassFilters() {
        when(doctorWorklistService.getWorklist(testUserId, "IN_PROGRESS", "URGENT", null)).thenReturn(List.of());

        ResponseEntity<ApiResponseWrapper<List<DoctorWorklistItemDTO>>> response =
                controller.getWorklist("IN_PROGRESS", "URGENT", null, doctorAuth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(requireBody(response).getData().isEmpty());
        verify(doctorWorklistService).getWorklist(testUserId, "IN_PROGRESS", "URGENT", null);
    }

    // ========== GET /api/me/patient-flow ==========

    @Test
    void getPatientFlow_shouldReturnFlowMap() {
        Map<String, List<PatientFlowItemDTO>> flow = new LinkedHashMap<>();
        flow.put("ARRIVED", List.of(PatientFlowItemDTO.builder().patientName("Pat B").build()));
        flow.put("IN_PROGRESS", List.of());
        when(patientFlowService.getPatientFlow(testUserId)).thenReturn(flow);

        ResponseEntity<ApiResponseWrapper<Map<String, List<PatientFlowItemDTO>>>> response =
                controller.getPatientFlow(doctorAuth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, List<PatientFlowItemDTO>> data = requireBody(response).getData();
        assertEquals(1, data.get("ARRIVED").size());
        verify(patientFlowService).getPatientFlow(testUserId);
    }

    // ========== GET /api/me/inbox ==========

    @Test
    void getInbox_shouldReturnInboxItems() {
        List<ClinicalInboxItemDTO> items = List.of(
                ClinicalInboxItemDTO.builder().category("MESSAGE").subject("2 unread messages").build());
        when(resultReviewService.getInboxItems(testUserId)).thenReturn(items);

        ResponseEntity<ApiResponseWrapper<List<ClinicalInboxItemDTO>>> response = controller.getInbox(doctorAuth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, requireBody(response).getData().size());
        verify(resultReviewService).getInboxItems(testUserId);
    }

    // ========== GET /api/me/results/review-queue ==========

    @Test
    void getResultReviewQueue_shouldReturnQueueItems() {
        List<DoctorResultQueueItemDTO> items = List.of(
                DoctorResultQueueItemDTO.builder().testName("CBC").patientName("Pat C").build());
        when(resultReviewService.getResultReviewQueue(testUserId)).thenReturn(items);

        ResponseEntity<ApiResponseWrapper<List<DoctorResultQueueItemDTO>>> response =
                controller.getResultReviewQueue(doctorAuth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, requireBody(response).getData().size());
        verify(resultReviewService).getResultReviewQueue(testUserId);
    }

    // ========== GET /api/me/patients/{patientId}/snapshot ==========

    @Test
    void getPatientSnapshot_shouldReturnSnapshot() {
        UUID patientId = UUID.randomUUID();
        PatientSnapshotDTO snapshot = PatientSnapshotDTO.builder()
                .patientId(patientId)
                .name("Alice Wong")
                .age(35)
                .build();
        when(patientSnapshotService.getSnapshot(patientId)).thenReturn(snapshot);

        ResponseEntity<ApiResponseWrapper<PatientSnapshotDTO>> response =
                controller.getPatientSnapshot(patientId, doctorAuth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Alice Wong", requireBody(response).getData().getName());
        verify(patientSnapshotService).getSnapshot(patientId);
    }
}
