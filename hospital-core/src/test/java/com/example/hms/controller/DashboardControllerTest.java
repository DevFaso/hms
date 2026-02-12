package com.example.hms.controller;

import com.example.hms.payload.dto.dashboard.DashboardConfigResponseDTO;
import com.example.hms.service.DashboardConfigurationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private DashboardConfigurationService dashboardConfigurationService;

    @InjectMocks
    private DashboardController controller;

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
}
