package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.PlatformRegistrySnapshotDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowRequestDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO;
import com.example.hms.service.SuperAdminPlatformRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminPlatformRegistryControllerTest {

    @Mock
    private SuperAdminPlatformRegistryService registryService;

    @InjectMocks
    private SuperAdminPlatformRegistryController controller;

    @Test
    void getRegistrySummaryReturnsServiceResponse() {
        SuperAdminPlatformRegistrySummaryDTO summary = SuperAdminPlatformRegistrySummaryDTO.builder()
            .modules(List.of())
            .automationTasks(List.of())
            .actions(SuperAdminPlatformRegistrySummaryDTO.ActionPanelDTO.builder()
                .totalIntegrations(5)
                .pendingIntegrations(2)
                .disabledLinks(1)
                .activeReleaseWindows(3)
                .lastSnapshotGeneratedAt("2025-10-02T10:00:00Z")
                .build())
            .build();

        when(registryService.getRegistrySummary()).thenReturn(summary);

        ResponseEntity<SuperAdminPlatformRegistrySummaryDTO> response = controller.getRegistrySummary();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(summary);
        verify(registryService).getRegistrySummary();
    }

    @Test
    void scheduleReleaseWindowCreatesResource() {
        PlatformReleaseWindowRequestDTO request = new PlatformReleaseWindowRequestDTO();
        request.setName("Q4 Freeze");
        request.setDescription("Coordinated rollout");
        request.setEnvironment("Production");
        request.setStartsAt(LocalDateTime.of(2025, 10, 10, 18, 0));
        request.setEndsAt(LocalDateTime.of(2025, 10, 10, 20, 0));
        request.setFreezeChanges(true);
        request.setOwnerTeam("Platform SRE");
        request.setNotes("Notify on-call");

        PlatformReleaseWindowResponseDTO responseDto = PlatformReleaseWindowResponseDTO.builder()
            .id(UUID.randomUUID())
            .name(request.getName())
            .description(request.getDescription())
            .environment(request.getEnvironment())
            .startsAt(request.getStartsAt())
            .endsAt(request.getEndsAt())
            .freezeChanges(request.isFreezeChanges())
            .ownerTeam(request.getOwnerTeam())
            .notes(request.getNotes())
            .build();

        when(registryService.scheduleReleaseWindow(request)).thenReturn(responseDto);

        ResponseEntity<PlatformReleaseWindowResponseDTO> response = controller.scheduleReleaseWindow(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(responseDto);
        verify(registryService).scheduleReleaseWindow(request);
    }

    @Test
    void exportSnapshotReturnsLatestSnapshot() {
        PlatformRegistrySnapshotDTO snapshot = PlatformRegistrySnapshotDTO.builder()
            .generatedAt(LocalDateTime.now())
            .summary(SuperAdminPlatformRegistrySummaryDTO.builder().build())
            .build();

        when(registryService.getRegistrySnapshot()).thenReturn(snapshot);

        ResponseEntity<PlatformRegistrySnapshotDTO> response = controller.exportSnapshot();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(snapshot);
        verify(registryService).getRegistrySnapshot();
    }
}
