package com.example.hms.service.impl;

import com.example.hms.enums.platform.PlatformReleaseStatus;
import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.model.platform.OrganizationPlatformService;
import com.example.hms.model.platform.PlatformReleaseWindow;
import com.example.hms.payload.dto.superadmin.PlatformRegistrySnapshotDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowRequestDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO.ActionPanelDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO.AutomationStatus;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO.AutomationTaskDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO.ModuleCardDTO;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.platform.DepartmentPlatformServiceLinkRepository;
import com.example.hms.repository.platform.HospitalPlatformServiceLinkRepository;
import com.example.hms.repository.platform.OrganizationPlatformServiceRepository;
import com.example.hms.repository.platform.PlatformReleaseWindowRepository;
import com.example.hms.service.SuperAdminPlatformRegistryService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

@Service
@RequiredArgsConstructor
@Transactional
public class SuperAdminPlatformRegistryServiceImpl implements SuperAdminPlatformRegistryService {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OrganizationPlatformServiceRepository organizationPlatformServiceRepository;
    private final HospitalPlatformServiceLinkRepository hospitalPlatformServiceLinkRepository;
    private final DepartmentPlatformServiceLinkRepository departmentPlatformServiceLinkRepository;
    private final NotificationRepository notificationRepository;
    private final PlatformReleaseWindowRepository platformReleaseWindowRepository;

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public SuperAdminPlatformRegistrySummaryDTO getRegistrySummary() {
        List<OrganizationPlatformService> services = organizationPlatformServiceRepository.findAll();
        long disabledHospitalLinks = hospitalPlatformServiceLinkRepository.countByEnabledFalse();
        long disabledDepartmentLinks = departmentPlatformServiceLinkRepository.countByEnabledFalse();
        long disabledLinks = disabledHospitalLinks + disabledDepartmentLinks;

        long unreadAlerts = notificationRepository.countByReadFalse();
        long staleAlerts = notificationRepository.countByReadFalseAndCreatedAtBefore(LocalDateTime.now().minusHours(4));

        List<PlatformReleaseWindow> activeWindows = platformReleaseWindowRepository.findByStatusIn(List.of(
            PlatformReleaseStatus.SCHEDULED,
            PlatformReleaseStatus.IN_PROGRESS
        ));
        long upcomingReleases = platformReleaseWindowRepository.countByEndsAtAfter(LocalDateTime.now());
        Optional<PlatformReleaseWindow> latestReleaseWindow = platformReleaseWindowRepository.findFirstByOrderByUpdatedAtDesc();
        LocalDateTime latestWindowTimestamp = latestReleaseWindow
            .map(window -> Optional.ofNullable(window.getUpdatedAt()).orElse(window.getCreatedAt()))
            .orElse(null);

        ModuleCardDTO clinical = buildModule(
            "Clinical modules",
            "Labs, pharmacy, imaging, telehealth, device integrations and remote monitoring.",
            services,
            service -> EnumSet.of(PlatformServiceType.EHR, PlatformServiceType.LIMS).contains(service.getServiceType())
        );
        clinical.setMeta("Feature flags per organization with environment-specific overrides.");

        ModuleCardDTO communications = buildModule(
            "Communication providers",
            "SMTP, SMS gateways, push notifications, patient messaging, voice bridges.",
            services,
            service -> {
                String provider = Objects.toString(service.getProvider(), "").toLowerCase(Locale.ENGLISH);
                String notes = Optional.ofNullable(service.getMetadata())
                    .map(meta -> Objects.toString(meta.getIntegrationNotes(), ""))
                    .orElse("")
                    .toLowerCase(Locale.ENGLISH);
                return provider.contains("sms")
                    || provider.contains("smtp")
                    || provider.contains("voice")
                    || notes.contains("sms")
                    || notes.contains("smtp")
                    || notes.contains("push")
                    || service.getServiceType() == PlatformServiceType.ANALYTICS;
            }
        );
        communications.setMeta("Support regional failover and escalation policies.");

        ModuleCardDTO terminology = buildModule(
            "Terminology packs",
            "ICD, CPT, SNOMED, LOINC, RxNorm, local formularies and allergy dictionaries.",
            services,
            service -> service.getServiceType() == PlatformServiceType.INVENTORY
                || Optional.ofNullable(service.getMetadata())
                    .map(meta -> Objects.toString(meta.getInventorySystem(), ""))
                    .map(value -> !value.isBlank())
                    .orElse(false)
        );
        terminology.setMeta("Versioned updates with preview windows and validation jobs.");

        List<AutomationTaskDTO> automation = List.of(
            buildAutomationTask(new AutomationTaskInput(
                "queue-health",
                "Queue health monitors",
                "Watch Kafka, RabbitMQ and email queue latencies.",
                unreadAlerts,
                staleAlerts,
                Thresholds.of(10, 30),
                "Unread alerts",
                unreadAlerts + " backlog",
                unreadAlerts > 30 ? "Scale worker pool and drain DLQ." : "Continuing to monitor backlog levels.",
                LocalDateTime.now().minusMinutes(5)
            )),
            buildAutomationTask(new AutomationTaskInput(
                "retry-spikes",
                "Retry spikes & dead-letter volumes",
                "Surface retry spikes and dead-letter volumes.",
                disabledLinks,
                disabledLinks,
                Thresholds.of(5, 15),
                "Disabled service links",
                disabledLinks + " offline",
                disabledLinks > 15 ? "Escalate to platform SRE for incident response." : "Verify credentials for affected links.",
                LocalDateTime.now().minusMinutes(12)
            )),
            buildReleaseAutomationTask(
                upcomingReleases,
                activeWindows.size(),
                Optional.ofNullable(latestWindowTimestamp).orElse(LocalDateTime.now())
            )
        );

        ActionPanelDTO actions = ActionPanelDTO.builder()
            .totalIntegrations(services.size())
            .pendingIntegrations(services.stream().filter(s -> s.getStatus() == PlatformServiceStatus.PENDING).count())
            .disabledLinks(disabledLinks)
            .activeReleaseWindows(activeWindows.size())
            .lastSnapshotGeneratedAt(Optional.ofNullable(latestWindowTimestamp).map(DISPLAY_FORMAT::format).orElse(null))
            .build();

        return SuperAdminPlatformRegistrySummaryDTO.builder()
            .modules(List.of(clinical, communications, terminology))
            .automationTasks(automation)
            .actions(actions)
            .build();
    }

    @Override
    public PlatformReleaseWindowResponseDTO scheduleReleaseWindow(PlatformReleaseWindowRequestDTO request) {
        if (request.getEndsAt().isBefore(request.getStartsAt())) {
            throw new IllegalArgumentException("Release window end time must be after the start time");
        }

        PlatformReleaseWindow releaseWindow = PlatformReleaseWindow.builder()
            .name(request.getName())
            .description(request.getDescription())
            .environment(request.getEnvironment())
            .startsAt(request.getStartsAt())
            .endsAt(request.getEndsAt())
            .status(resolveStatusForWindow(request.getStartsAt(), request.getEndsAt()))
            .freezeChanges(request.isFreezeChanges())
            .ownerTeam(request.getOwnerTeam())
            .notes(request.getNotes())
            .build();

        PlatformReleaseWindow saved = platformReleaseWindowRepository.save(releaseWindow);
        return mapReleaseWindow(saved);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public PlatformRegistrySnapshotDTO getRegistrySnapshot() {
        return PlatformRegistrySnapshotDTO.builder()
            .generatedAt(LocalDateTime.now())
            .summary(getRegistrySummary())
            .build();
    }

    private ModuleCardDTO buildModule(String title, String description, List<OrganizationPlatformService> services, Predicate<OrganizationPlatformService> filter) {
        List<OrganizationPlatformService> scoped = services.stream().filter(filter).toList();
        long active = scoped.stream().filter(service -> service.getStatus() == PlatformServiceStatus.ACTIVE).count();
        long pending = scoped.stream().filter(service -> service.getStatus() == PlatformServiceStatus.PENDING).count();
        long managed = scoped.stream().filter(OrganizationPlatformService::isManagedByPlatform).count();

        return ModuleCardDTO.builder()
            .title(title)
            .description(description)
            .meta(String.format(Locale.ENGLISH, "%d active • %d pending • %d managed", active, pending, managed))
            .activeIntegrations(active)
            .pendingIntegrations(pending)
            .managedIntegrations(managed)
            .build();
    }

    private AutomationTaskDTO buildAutomationTask(AutomationTaskInput input) {
        AutomationStatus status = evaluateStatus(input.metric(), input.secondaryMetric(), input.thresholds());
        return AutomationTaskDTO.builder()
            .id(input.id())
            .title(input.title())
            .description(input.description())
            .status(status)
            .statusLabel(toStatusLabel(status))
            .metricLabel(input.metricLabel())
            .metricValue(input.metricValue())
            .nextAction(input.nextAction())
            .lastRun(DISPLAY_FORMAT.format(input.lastRun()))
            .build();
    }

    private AutomationStatus evaluateStatus(long metric, long secondaryMetric, Thresholds thresholds) {
        long effective = Math.max(metric, secondaryMetric);
        if (effective >= thresholds.critical) {
            return AutomationStatus.BLOCKED;
        }
        if (effective >= thresholds.warning) {
            return AutomationStatus.AT_RISK;
        }
        return AutomationStatus.ON_TRACK;
    }

    private String toStatusLabel(AutomationStatus status) {
        return switch (status) {
            case ON_TRACK -> "On track";
            case AT_RISK -> "At risk";
            case BLOCKED -> "Blocked";
        };
    }

    private AutomationTaskDTO buildReleaseAutomationTask(long upcomingReleases, long activeWindows, LocalDateTime lastRun) {
        AutomationStatus status;
        if (upcomingReleases == 0) {
            status = AutomationStatus.AT_RISK;
        } else if (activeWindows > 6) {
            status = AutomationStatus.BLOCKED;
        } else {
            status = AutomationStatus.ON_TRACK;
        }

        return AutomationTaskDTO.builder()
            .id("release-windows")
            .title("Maintenance windows & deployment locks")
            .description("Expose maintenance windows and deployment locks.")
            .status(status)
            .statusLabel(toStatusLabel(status))
            .metricLabel("Upcoming windows")
            .metricValue(upcomingReleases + " scheduled")
            .nextAction(upcomingReleases == 0 ? "Create a new maintenance window." : "Communicate freeze schedule to delivery teams.")
            .lastRun(DISPLAY_FORMAT.format(lastRun))
            .build();
    }

    private PlatformReleaseStatus resolveStatusForWindow(LocalDateTime startsAt, LocalDateTime endsAt) {
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(endsAt)) {
            return PlatformReleaseStatus.COMPLETED;
        }
        if (!now.isBefore(startsAt) && !now.isAfter(endsAt)) {
            return PlatformReleaseStatus.IN_PROGRESS;
        }
        return PlatformReleaseStatus.SCHEDULED;
    }

    private PlatformReleaseWindowResponseDTO mapReleaseWindow(PlatformReleaseWindow window) {
        return PlatformReleaseWindowResponseDTO.builder()
            .id(window.getId())
            .name(window.getName())
            .description(window.getDescription())
            .environment(window.getEnvironment())
            .startsAt(window.getStartsAt())
            .endsAt(window.getEndsAt())
            .status(window.getStatus())
            .freezeChanges(window.isFreezeChanges())
            .ownerTeam(window.getOwnerTeam())
            .notes(window.getNotes())
            .createdAt(window.getCreatedAt())
            .updatedAt(window.getUpdatedAt())
            .build();
    }

    private record Thresholds(long warning, long critical) {
        static Thresholds of(long warning, long critical) {
            return new Thresholds(warning, critical);
        }
    }

    private record AutomationTaskInput(
        String id,
        String title,
        String description,
        long metric,
        long secondaryMetric,
        Thresholds thresholds,
        String metricLabel,
        String metricValue,
        String nextAction,
        LocalDateTime lastRun
    ) {}
}
