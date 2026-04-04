package com.example.hms.controller;

import com.example.hms.payload.dto.dashboard.DashboardConfigResponseDTO;
import com.example.hms.payload.dto.dashboard.LabDirectorDashboardDTO;
import com.example.hms.payload.dto.dashboard.QualityManagerDashboardDTO;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.DashboardConfigurationService;
import com.example.hms.service.HospitalAdminDashboardService;
import com.example.hms.service.LabDirectorDashboardService;
import com.example.hms.service.QualityManagerDashboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardControllerTest {

    @Mock private DashboardConfigurationService dashboardConfigurationService;
    @Mock private HospitalAdminDashboardService hospitalAdminDashboardService;
    @Mock private LabDirectorDashboardService labDirectorDashboardService;
    @Mock private QualityManagerDashboardService qualityManagerDashboardService;

    @InjectMocks private DashboardController controller;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private MockedStatic<HospitalContextHolder> contextHolderMock;

    @BeforeEach
    void setupHospitalContext() {
        contextHolderMock = Mockito.mockStatic(HospitalContextHolder.class);
        HospitalContext ctx = Mockito.mock(HospitalContext.class);
        when(ctx.getActiveHospitalId()).thenReturn(HOSPITAL_ID);
        contextHolderMock.when(HospitalContextHolder::getContext).thenReturn(Optional.of(ctx));
    }

    @AfterEach
    void tearDownContext() {
        contextHolderMock.close();
    }

    // ── /dashboard/me ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getDashboardForCurrentUser returns 200 OK with response body")
    void getDashboardReturnsOk() {
        DashboardConfigResponseDTO dto = new DashboardConfigResponseDTO(
            UUID.randomUUID(), "ROLE_DOCTOR", List.of(), List.of("VIEW_PATIENTS")
        );
        when(dashboardConfigurationService.getDashboardForCurrentUser()).thenReturn(dto);

        ResponseEntity<DashboardConfigResponseDTO> result = controller.getDashboardForCurrentUser();

        assertAll(
            () -> assertEquals(HttpStatus.OK, result.getStatusCode()),
            () -> assertSame(dto, result.getBody())
        );
        verify(dashboardConfigurationService).getDashboardForCurrentUser();
    }

    @Test
    @DisplayName("getDashboardForCurrentUser delegates to service")
    void delegatesToService() {
        DashboardConfigResponseDTO dto = new DashboardConfigResponseDTO(
            UUID.randomUUID(), "ROLE_ADMIN", List.of(), List.of()
        );
        when(dashboardConfigurationService.getDashboardForCurrentUser()).thenReturn(dto);

        ResponseEntity<DashboardConfigResponseDTO> result = controller.getDashboardForCurrentUser();

        assertNotNull(result.getBody());
        assertEquals("ROLE_ADMIN", result.getBody().primaryRoleCode());
        verify(dashboardConfigurationService, times(1)).getDashboardForCurrentUser();
    }

    @Test
    @DisplayName("getDashboardForCurrentUser returns null body when service returns null")
    void serviceReturnsNull() {
        when(dashboardConfigurationService.getDashboardForCurrentUser()).thenReturn(null);

        ResponseEntity<DashboardConfigResponseDTO> result = controller.getDashboardForCurrentUser();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNull(result.getBody());
    }

    // ── /dashboard/lab-director/summary ──────────────────────────────────────

    @Test
    @DisplayName("getLabDirectorSummary returns 200 OK with DTO")
    void labDirectorSummaryReturnsOk() {
        LabDirectorDashboardDTO dto = LabDirectorDashboardDTO.builder()
                .hospitalId(HOSPITAL_ID)
                .asOfDate(LocalDate.now())
                .pendingDirectorApproval(3L)
                .ordersToday(50L)
                .build();
        when(labDirectorDashboardService.getSummary(HOSPITAL_ID)).thenReturn(dto);

        ResponseEntity<LabDirectorDashboardDTO> result = controller.getLabDirectorSummary();

        assertAll(
            () -> assertEquals(HttpStatus.OK, result.getStatusCode()),
            () -> assertSame(dto, result.getBody()),
            () -> assertEquals(3L, result.getBody().getPendingDirectorApproval()),
            () -> assertEquals(50L, result.getBody().getOrdersToday())
        );
        verify(labDirectorDashboardService).getSummary(HOSPITAL_ID);
    }

    @Test
    @DisplayName("getLabDirectorSummary throws when no hospital context")
    void labDirectorSummaryThrowsWithoutContext() {
        contextHolderMock.when(HospitalContextHolder::getContext).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> controller.getLabDirectorSummary());
    }

    // ── /dashboard/quality-manager/summary ───────────────────────────────────

    @Test
    @DisplayName("getQualityManagerSummary returns 200 OK with DTO")
    void qualityManagerSummaryReturnsOk() {
        QualityManagerDashboardDTO dto = QualityManagerDashboardDTO.builder()
                .hospitalId(HOSPITAL_ID)
                .asOfDate(LocalDate.now())
                .pendingQaReview(5L)
                .qualityPassRate(87.5)
                .build();
        when(qualityManagerDashboardService.getSummary(HOSPITAL_ID)).thenReturn(dto);

        ResponseEntity<QualityManagerDashboardDTO> result = controller.getQualityManagerSummary();

        assertAll(
            () -> assertEquals(HttpStatus.OK, result.getStatusCode()),
            () -> assertSame(dto, result.getBody()),
            () -> assertEquals(5L, result.getBody().getPendingQaReview()),
            () -> assertEquals(87.5, result.getBody().getQualityPassRate())
        );
        verify(qualityManagerDashboardService).getSummary(HOSPITAL_ID);
    }

    @Test
    @DisplayName("getQualityManagerSummary throws when no hospital context")
    void qualityManagerSummaryThrowsWithoutContext() {
        contextHolderMock.when(HospitalContextHolder::getContext).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> controller.getQualityManagerSummary());
    }

    @Test
    @DisplayName("getQualityManagerSummary passes hospitalId from context to service")
    void qualityManagerSummaryPassesHospitalId() {
        QualityManagerDashboardDTO dto = QualityManagerDashboardDTO.builder()
                .hospitalId(HOSPITAL_ID).build();
        when(qualityManagerDashboardService.getSummary(HOSPITAL_ID)).thenReturn(dto);

        controller.getQualityManagerSummary();

        verify(qualityManagerDashboardService, times(1)).getSummary(HOSPITAL_ID);
    }
}

