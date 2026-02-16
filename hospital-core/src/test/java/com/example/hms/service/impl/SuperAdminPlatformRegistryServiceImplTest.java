package com.example.hms.service.impl;

import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.platform.PlatformReleaseStatus;
import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.model.Organization;
import com.example.hms.model.embedded.PlatformServiceMetadata;
import com.example.hms.model.platform.OrganizationPlatformService;
import com.example.hms.model.platform.PlatformReleaseWindow;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowRequestDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.platform.DepartmentPlatformServiceLinkRepository;
import com.example.hms.repository.platform.HospitalPlatformServiceLinkRepository;
import com.example.hms.repository.platform.OrganizationPlatformServiceRepository;
import com.example.hms.repository.platform.PlatformReleaseWindowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminPlatformRegistryServiceImplTest {

    @Mock
    private OrganizationPlatformServiceRepository organizationPlatformServiceRepository;
    @Mock
    private HospitalPlatformServiceLinkRepository hospitalPlatformServiceLinkRepository;
    @Mock
    private DepartmentPlatformServiceLinkRepository departmentPlatformServiceLinkRepository;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private PlatformReleaseWindowRepository platformReleaseWindowRepository;

    @InjectMocks
    private SuperAdminPlatformRegistryServiceImpl service;

    private OrganizationPlatformService activeEhrService;
    private OrganizationPlatformService pendingLimsService;
    private OrganizationPlatformService managedInventoryService;

    @BeforeEach
    void setUp() {
        Organization organization = Organization.builder()
            .name("Northbridge Health")
            .code("NBH")
            .type(OrganizationType.HOSPITAL_CHAIN)
            .build();
        organization.setId(UUID.randomUUID());

        activeEhrService = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.EHR)
            .status(PlatformServiceStatus.ACTIVE)
            .provider("Epic")
            .managedByPlatform(true)
            .build();

        pendingLimsService = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.LIMS)
            .status(PlatformServiceStatus.PENDING)
            .provider("LabSync")
            .managedByPlatform(false)
            .build();

        managedInventoryService = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.INVENTORY)
            .status(PlatformServiceStatus.ACTIVE)
            .provider("TerminologyHub")
            .managedByPlatform(true)
            .metadata(PlatformServiceMetadata.builder()
                .inventorySystem("SNOMED")
                .build())
            .build();
    }

    @Test
    void getRegistrySummaryAggregatesMetrics() {
        when(organizationPlatformServiceRepository.findAll())
            .thenReturn(List.of(activeEhrService, pendingLimsService, managedInventoryService));
        when(hospitalPlatformServiceLinkRepository.countByEnabledFalse()).thenReturn(3L);
        when(departmentPlatformServiceLinkRepository.countByEnabledFalse()).thenReturn(2L);
        when(notificationRepository.countByReadFalse()).thenReturn(7L);
        when(notificationRepository.countByReadFalseAndCreatedAtBefore(any(LocalDateTime.class))).thenReturn(2L);

        PlatformReleaseWindow releaseWindow = PlatformReleaseWindow.builder()
            .name("Q4 Freeze")
            .environment("production")
            .startsAt(LocalDateTime.now().minusDays(1))
            .endsAt(LocalDateTime.now().plusDays(1))
            .status(PlatformReleaseStatus.IN_PROGRESS)
            .build();
        releaseWindow.setCreatedAt(LocalDateTime.now().minusDays(2));
        releaseWindow.setUpdatedAt(LocalDateTime.now().minusHours(3));

        when(platformReleaseWindowRepository.findByStatusIn(any()))
            .thenReturn(List.of(releaseWindow));
        when(platformReleaseWindowRepository.countByEndsAtAfter(any(LocalDateTime.class))).thenReturn(1L);
        when(platformReleaseWindowRepository.findFirstByOrderByUpdatedAtDesc()).thenReturn(Optional.of(releaseWindow));

        SuperAdminPlatformRegistrySummaryDTO summary = service.getRegistrySummary();

        assertThat(summary.getModules()).hasSize(3);
        SuperAdminPlatformRegistrySummaryDTO.ModuleCardDTO clinical = summary.getModules().get(0);
        assertThat(clinical.getActiveIntegrations()).isEqualTo(1);
        assertThat(clinical.getPendingIntegrations()).isEqualTo(1);

        SuperAdminPlatformRegistrySummaryDTO.AutomationTaskDTO queueTask = summary.getAutomationTasks().get(0);
        assertThat(queueTask.getMetricValue()).isEqualTo("7 backlog");

        SuperAdminPlatformRegistrySummaryDTO.ActionPanelDTO actions = summary.getActions();
        assertThat(actions.getTotalIntegrations()).isEqualTo(3);
        assertThat(actions.getDisabledLinks()).isEqualTo(5);
        assertThat(actions.getActiveReleaseWindows()).isEqualTo(1);
        assertThat(actions.getLastSnapshotGeneratedAt()).isNotBlank();
    }

    @Test
    void scheduleReleaseWindowPersistsAndReturnsResponse() {
        PlatformReleaseWindowRequestDTO request = new PlatformReleaseWindowRequestDTO();
        request.setName("Q1 Cutover");
        request.setEnvironment("staging");
        request.setStartsAt(LocalDateTime.now().plusDays(2));
        request.setEndsAt(LocalDateTime.now().plusDays(3));
        request.setFreezeChanges(true);
        request.setOwnerTeam("Platform");
        request.setNotes("Coordinate with billing team.");

        PlatformReleaseWindow persisted = PlatformReleaseWindow.builder()
            .name(request.getName())
            .environment(request.getEnvironment())
            .startsAt(request.getStartsAt())
            .endsAt(request.getEndsAt())
            .status(PlatformReleaseStatus.SCHEDULED)
            .freezeChanges(true)
            .ownerTeam("Platform")
            .notes("Coordinate with billing team.")
            .build();
        persisted.setId(UUID.randomUUID());
        persisted.setCreatedAt(LocalDateTime.now());
        persisted.setUpdatedAt(LocalDateTime.now());

        when(platformReleaseWindowRepository.save(any(PlatformReleaseWindow.class))).thenReturn(persisted);

        PlatformReleaseWindowResponseDTO response = service.scheduleReleaseWindow(request);

        assertThat(response.getId()).isEqualTo(persisted.getId());
        assertThat(response.getStatus()).isEqualTo(PlatformReleaseStatus.SCHEDULED);
        assertThat(response.isFreezeChanges()).isTrue();
        assertThat(response.getEnvironment()).isEqualTo("staging");
    }

    @Test
    void scheduleReleaseWindowRejectsInvalidRange() {
        PlatformReleaseWindowRequestDTO request = new PlatformReleaseWindowRequestDTO();
        request.setName("Invalid Window");
        request.setEnvironment("production");
        request.setStartsAt(LocalDateTime.now().plusDays(2));
        request.setEndsAt(LocalDateTime.now().plusDays(1));

        assertThatThrownBy(() -> service.scheduleReleaseWindow(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("end time must be after the start time");
    }
}
