package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.platform.PlatformReleaseStatus;
import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Organization;
import com.example.hms.model.platform.OrganizationPlatformService;
import com.example.hms.model.platform.PlatformReleaseWindow;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowRequestDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO.AutomationTaskDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO.ModuleCardDTO;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.platform.DepartmentPlatformServiceLinkRepository;
import com.example.hms.repository.platform.HospitalPlatformServiceLinkRepository;
import com.example.hms.repository.platform.OrganizationPlatformServiceRepository;
import com.example.hms.repository.platform.PlatformReleaseWindowRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock
    private AuditEventLogService auditEventLogService;

    @Spy private MessageSource messageSource = TestMessageSources.bundles();

    @InjectMocks
    private SuperAdminPlatformRegistryServiceImpl service;

    private Organization organization;

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @BeforeEach
    void setUp() {
        // Dashboard copy follows the request locale; pin English for the
        // literal assertions below.
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        organization = Organization.builder()
            .name("Northbridge Health")
            .code("NBH")
            .type(OrganizationType.HOSPITAL_CHAIN)
            .build();
        organization.setId(UUID.randomUUID());
    }

    private OrganizationPlatformService service(PlatformServiceType type, PlatformServiceStatus status, boolean managed) {
        return OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(type)
            .status(status)
            .managedByPlatform(managed)
            .build();
    }

    private void stubSummaryCounts() {
        when(hospitalPlatformServiceLinkRepository.countByEnabledFalse()).thenReturn(3L);
        when(departmentPlatformServiceLinkRepository.countByEnabledFalse()).thenReturn(2L);
        when(notificationRepository.countByReadFalse()).thenReturn(7L);
        when(notificationRepository.countByReadFalseAndCreatedAtBefore(any(LocalDateTime.class))).thenReturn(2L);
        when(platformReleaseWindowRepository.countByStatusNotAndEndsAtAfter(eq(PlatformReleaseStatus.CANCELLED), any()))
            .thenReturn(2L);
        when(platformReleaseWindowRepository.countByStatusNotAndStartsAtAfter(eq(PlatformReleaseStatus.CANCELLED), any()))
            .thenReturn(1L);
    }

    private PlatformReleaseWindowRequestDTO request(String name, LocalDateTime startsAt, LocalDateTime endsAt) {
        PlatformReleaseWindowRequestDTO request = new PlatformReleaseWindowRequestDTO();
        request.setName(name);
        request.setEnvironment("production");
        request.setStartsAt(startsAt);
        request.setEndsAt(endsAt);
        request.setFreezeChanges(true);
        request.setOwnerTeam("Platform");
        return request;
    }

    private PlatformReleaseWindow persisted(PlatformReleaseWindowRequestDTO request) {
        PlatformReleaseWindow window = PlatformReleaseWindow.builder()
            .name(request.getName())
            .environment(request.getEnvironment())
            .startsAt(request.getStartsAt())
            .endsAt(request.getEndsAt())
            .status(PlatformReleaseStatus.SCHEDULED)
            .freezeChanges(request.isFreezeChanges())
            .ownerTeam(request.getOwnerTeam())
            .build();
        window.setId(UUID.randomUUID());
        window.setCreatedAt(LocalDateTime.now());
        window.setUpdatedAt(LocalDateTime.now());
        return window;
    }

    @Test
    @DisplayName("summary: counts derived from the clock, honest labels, no fabricated last run (D7/D8)")
    void getRegistrySummaryAggregatesMetrics() {
        when(organizationPlatformServiceRepository.findAll()).thenReturn(List.of(
            service(PlatformServiceType.EHR, PlatformServiceStatus.ACTIVE, true),
            service(PlatformServiceType.LIMS, PlatformServiceStatus.PENDING, false),
            service(PlatformServiceType.INVENTORY, PlatformServiceStatus.ACTIVE, true)));
        stubSummaryCounts();
        PlatformReleaseWindow latest = persisted(request("Q4 Freeze",
            LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1)));
        latest.setUpdatedAt(LocalDateTime.of(2026, 10, 1, 9, 30));
        when(platformReleaseWindowRepository.findFirstByOrderByUpdatedAtDesc()).thenReturn(Optional.of(latest));

        SuperAdminPlatformRegistrySummaryDTO summary = service.getRegistrySummary();

        assertThat(summary.getAutomationTasks()).extracting(AutomationTaskDTO::getId)
            .containsExactly("unread-notifications", "disabled-links", "release-windows");
        AutomationTaskDTO unread = summary.getAutomationTasks().get(0);
        assertThat(unread.getTitle()).isEqualTo("Unread notifications");
        assertThat(unread.getMetricValue()).isEqualTo("7 unread (2 older than 4 hours)");
        AutomationTaskDTO disabled = summary.getAutomationTasks().get(1);
        assertThat(disabled.getTitle()).isEqualTo("Disabled service links");
        assertThat(disabled.getMetricValue()).isEqualTo("5 disabled");
        AutomationTaskDTO releases = summary.getAutomationTasks().get(2);
        assertThat(releases.getMetricValue()).isEqualTo("2 open (1 not started yet)");

        assertThat(summary.getActions().getTotalIntegrations()).isEqualTo(3);
        assertThat(summary.getActions().getPendingIntegrations()).isEqualTo(1);
        assertThat(summary.getActions().getDisabledLinks()).isEqualTo(5);
        assertThat(summary.getActions().getActiveReleaseWindows()).isEqualTo(2);
        assertThat(summary.getActions().getLastReleaseWindowChangeAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 9, 30));
        verify(platformReleaseWindowRepository, never()).findByStatusIn(any());
    }

    @Test
    @DisplayName("module cards partition the service types and count what their titles say (D8)")
    void moduleCardsMatchTheirLabels() {
        when(organizationPlatformServiceRepository.findAll()).thenReturn(List.of(
            service(PlatformServiceType.EHR, PlatformServiceStatus.ACTIVE, true),
            service(PlatformServiceType.LIMS, PlatformServiceStatus.PENDING, false),
            service(PlatformServiceType.PEDIATRIC_MESSAGING, PlatformServiceStatus.ACTIVE, false),
            // ANALYTICS used to be counted as a communication provider and
            // INVENTORY as a terminology pack.
            service(PlatformServiceType.ANALYTICS, PlatformServiceStatus.ACTIVE, true),
            service(PlatformServiceType.INVENTORY, PlatformServiceStatus.PENDING, true)));
        stubSummaryCounts();
        when(platformReleaseWindowRepository.findFirstByOrderByUpdatedAtDesc()).thenReturn(Optional.empty());

        List<ModuleCardDTO> modules = service.getRegistrySummary().getModules();

        assertThat(modules).extracting(ModuleCardDTO::getTitle)
            .containsExactly("Clinical modules", "Communication providers", "Operations");
        assertThat(modules.get(0).getActiveIntegrations()).isEqualTo(1);
        assertThat(modules.get(0).getPendingIntegrations()).isEqualTo(1);
        assertThat(modules.get(1).getActiveIntegrations()).isEqualTo(1);
        assertThat(modules.get(1).getPendingIntegrations()).isZero();
        assertThat(modules.get(2).getActiveIntegrations()).isEqualTo(1);
        assertThat(modules.get(2).getPendingIntegrations()).isEqualTo(1);
        assertThat(modules.get(2).getManagedIntegrations()).isEqualTo(2);
        long counted = modules.stream()
            .mapToLong(m -> m.getActiveIntegrations() + m.getPendingIntegrations())
            .sum();
        assertThat(counted).as("every service is counted exactly once").isEqualTo(5);
    }

    @Test
    @DisplayName("summary copy follows the request locale")
    void summaryIsLocalized() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        when(organizationPlatformServiceRepository.findAll()).thenReturn(List.of());
        stubSummaryCounts();
        when(platformReleaseWindowRepository.findFirstByOrderByUpdatedAtDesc()).thenReturn(Optional.empty());

        SuperAdminPlatformRegistrySummaryDTO summary = service.getRegistrySummary();

        assertThat(summary.getAutomationTasks().get(0).getTitle()).isEqualTo("Notifications non lues");
        assertThat(summary.getModules().get(2).getTitle()).isEqualTo("Opérations");
        assertThat(summary.getActions().getLastReleaseWindowChangeAt()).isNull();
    }

    @Test
    @DisplayName("a window's status follows the clock at read; CANCELLED stays (D7)")
    void statusIsDerivedAtRead() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 3, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 3, 12, 0);

        assertThat(SuperAdminPlatformRegistryServiceImpl.statusAt(PlatformReleaseStatus.SCHEDULED, start, end,
            start.minusMinutes(1))).isEqualTo(PlatformReleaseStatus.SCHEDULED);
        assertThat(SuperAdminPlatformRegistryServiceImpl.statusAt(PlatformReleaseStatus.SCHEDULED, start, end,
            start)).isEqualTo(PlatformReleaseStatus.IN_PROGRESS);
        assertThat(SuperAdminPlatformRegistryServiceImpl.statusAt(PlatformReleaseStatus.SCHEDULED, start, end,
            end)).isEqualTo(PlatformReleaseStatus.IN_PROGRESS);
        assertThat(SuperAdminPlatformRegistryServiceImpl.statusAt(PlatformReleaseStatus.IN_PROGRESS, start, end,
            end.plusMinutes(1))).isEqualTo(PlatformReleaseStatus.COMPLETED);
        assertThat(SuperAdminPlatformRegistryServiceImpl.statusAt(PlatformReleaseStatus.CANCELLED, start, end,
            start.plusMinutes(30))).isEqualTo(PlatformReleaseStatus.CANCELLED);
    }

    @Test
    @DisplayName("the list reports each window's status now, not the one stamped at creation (D6/D7)")
    void listReleaseWindowsDerivesStatus() {
        PlatformReleaseWindow past = persisted(request("Done", LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(2)));
        past.setStatus(PlatformReleaseStatus.SCHEDULED); // stamped at creation, never advanced
        PlatformReleaseWindow running = persisted(request("Now", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)));
        running.setStatus(PlatformReleaseStatus.SCHEDULED);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        when(platformReleaseWindowRepository.findAll(page.capture())).thenReturn(new PageImpl<>(List.of(running, past)));

        List<PlatformReleaseWindowResponseDTO> list = service.listReleaseWindows(null);

        assertThat(list).extracting(PlatformReleaseWindowResponseDTO::getName).containsExactly("Now", "Done");
        assertThat(list).extracting(PlatformReleaseWindowResponseDTO::getStatus)
            .containsExactly(PlatformReleaseStatus.IN_PROGRESS, PlatformReleaseStatus.COMPLETED);
        assertThat(page.getValue().getPageSize()).isEqualTo(SuperAdminPlatformRegistryServiceImpl.DEFAULT_RELEASE_WINDOW_LIMIT);
        assertThat(page.getValue().getSort().getOrderFor("startsAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("the list limit is capped")
    void listReleaseWindowsCapsTheLimit() {
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        when(platformReleaseWindowRepository.findAll(page.capture())).thenReturn(new PageImpl<>(List.of()));

        service.listReleaseWindows(10_000);
        assertThat(page.getValue().getPageSize()).isEqualTo(SuperAdminPlatformRegistryServiceImpl.MAX_RELEASE_WINDOW_LIMIT);

        service.listReleaseWindows(5);
        assertThat(page.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void scheduleReleaseWindowPersistsAndReturnsResponse() {
        PlatformReleaseWindowRequestDTO request = request("Q1 Cutover",
            LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(3));
        PlatformReleaseWindow persisted = persisted(request);
        when(platformReleaseWindowRepository.saveAndFlush(any(PlatformReleaseWindow.class))).thenReturn(persisted);

        PlatformReleaseWindowResponseDTO response = service.scheduleReleaseWindow(request);

        assertThat(response.getId()).isEqualTo(persisted.getId());
        assertThat(response.getStatus()).isEqualTo(PlatformReleaseStatus.SCHEDULED);
        assertThat(response.isFreezeChanges()).isTrue();
        assertThat(response.getEnvironment()).isEqualTo("production");
    }

    @Test
    @DisplayName("an end before the start is a keyed 400, in the caller's language (D12)")
    void scheduleReleaseWindowRejectsInvalidRange() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        PlatformReleaseWindowRequestDTO request = request("Invalid Window",
            LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(1));

        assertThatThrownBy(() -> service.scheduleReleaseWindow(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("La fenêtre de mise en production doit se terminer après son début.");
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    @DisplayName("a window that starts and ends at the same instant is refused too")
    void scheduleReleaseWindowRejectsEmptyRange() {
        LocalDateTime at = LocalDateTime.now().plusDays(2);
        PlatformReleaseWindowRequestDTO request = request("Empty", at, at);
        assertThatThrownBy(() -> service.scheduleReleaseWindow(request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a duplicate name + environment is a 409 and leaves no audit row and no insert (D7b)")
    void duplicateWindowLeavesNoAuditRow() {
        PlatformReleaseWindowRequestDTO request = request("Q2 Cutover",
            LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(3));
        when(platformReleaseWindowRepository.existsByNameAndEnvironment("Q2 Cutover", "production")).thenReturn(true);

        assertThatThrownBy(() -> service.scheduleReleaseWindow(request))
            .isInstanceOf(ConflictException.class)
            .hasMessage("A release window with this name already exists in this environment.");
        verify(platformReleaseWindowRepository, never()).saveAndFlush(any());
        verify(platformReleaseWindowRepository, never()).save(any());
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    @DisplayName("the audit row is written only once the schedule commits (D7b)")
    void auditWaitsForTheCommit() {
        PlatformReleaseWindowRequestDTO request = request("Q2 Cutover",
            LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(3));
        when(platformReleaseWindowRepository.saveAndFlush(any(PlatformReleaseWindow.class))).thenReturn(persisted(request));

        TransactionSynchronizationManager.initSynchronization();
        service.scheduleReleaseWindow(request);

        // Still inside the transaction: a rollback from here must not leave a row.
        verifyNoInteractions(auditEventLogService);

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<AuditEventRequestDTO> cap = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(AuditEventType.PLATFORM_REGISTRY_UPDATED);
        assertThat(cap.getValue().getEntityType()).isEqualTo("PLATFORM_RELEASE_WINDOW");
        assertThat(cap.getValue().getResourceName()).isEqualTo("Q2 Cutover");
    }

    @Test
    void scheduleReleaseWindowSucceedsEvenWhenAuditEmissionThrows() {
        PlatformReleaseWindowRequestDTO request = request("Q3 Cutover",
            LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(3));
        PlatformReleaseWindow persisted = persisted(request);
        when(platformReleaseWindowRepository.saveAndFlush(any(PlatformReleaseWindow.class))).thenReturn(persisted);
        doThrow(new RuntimeException("audit pipeline down")).when(auditEventLogService).logEvent(any());

        // Audit failure must not fail the release-window write —
        // operator action is the source of truth, audit is best-effort.
        PlatformReleaseWindowResponseDTO response = service.scheduleReleaseWindow(request);
        assertThat(response.getId()).isEqualTo(persisted.getId());
    }

    @Test
    void snapshotWrapsTheSummary() {
        when(organizationPlatformServiceRepository.findAll()).thenReturn(List.of());
        stubSummaryCounts();
        when(platformReleaseWindowRepository.findFirstByOrderByUpdatedAtDesc()).thenReturn(Optional.empty());

        var snapshot = service.getRegistrySnapshot();

        assertThat(snapshot.getGeneratedAt()).isNotNull();
        assertThat(snapshot.getSummary().getActions().getActiveReleaseWindows()).isEqualTo(2);
    }
}
