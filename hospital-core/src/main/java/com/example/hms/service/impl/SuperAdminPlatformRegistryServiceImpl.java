package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.platform.PlatformReleaseStatus;
import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.model.platform.OrganizationPlatformService;
import com.example.hms.model.platform.PlatformReleaseWindow;
import com.example.hms.payload.dto.AuditEventRequestDTO;
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
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.SuperAdminPlatformRegistryService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SuperAdminPlatformRegistryServiceImpl implements SuperAdminPlatformRegistryService {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String SYSTEM_ACTOR = "system";

    private final OrganizationPlatformServiceRepository organizationPlatformServiceRepository;
    private final HospitalPlatformServiceLinkRepository hospitalPlatformServiceLinkRepository;
    private final DepartmentPlatformServiceLinkRepository departmentPlatformServiceLinkRepository;
    private final NotificationRepository notificationRepository;
    private final PlatformReleaseWindowRepository platformReleaseWindowRepository;
    private final AuditEventLogService auditEventLogService;
    private final MessageSource messageSource;

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

        // Every string below is read by the super-admin who requested the
        // summary, so the request locale applies.
        Locale locale = LocaleContextHolder.getLocale();

        ModuleCardDTO clinical = buildModule(
            "platform.module.clinical",
            services,
            service -> EnumSet.of(PlatformServiceType.EHR, PlatformServiceType.LIMS).contains(service.getServiceType()),
            locale
        );
        clinical.setMeta(text("platform.module.clinical.meta", locale));

        ModuleCardDTO communications = buildModule(
            "platform.module.communications",
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
            },
            locale
        );
        communications.setMeta(text("platform.module.communications.meta", locale));

        ModuleCardDTO terminology = buildModule(
            "platform.module.terminology",
            services,
            service -> service.getServiceType() == PlatformServiceType.INVENTORY
                || Optional.ofNullable(service.getMetadata())
                    .map(meta -> Objects.toString(meta.getInventorySystem(), ""))
                    .map(value -> !value.isBlank())
                    .orElse(false),
            locale
        );
        terminology.setMeta(text("platform.module.terminology.meta", locale));

        List<AutomationTaskDTO> automation = List.of(
            buildAutomationTask(new AutomationTaskInput(
                "queue-health",
                text("platform.automation.queueHealth.title", locale),
                text("platform.automation.queueHealth.description", locale),
                unreadAlerts,
                staleAlerts,
                Thresholds.of(10, 30),
                text("platform.automation.queueHealth.metricLabel", locale),
                text("platform.automation.queueHealth.metricValue", locale, String.valueOf(unreadAlerts)),
                text(unreadAlerts > 30
                    ? "platform.automation.queueHealth.nextAction.critical"
                    : "platform.automation.queueHealth.nextAction.normal", locale),
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5)
            ), locale),
            buildAutomationTask(new AutomationTaskInput(
                "retry-spikes",
                text("platform.automation.retrySpikes.title", locale),
                text("platform.automation.retrySpikes.description", locale),
                disabledLinks,
                disabledLinks,
                Thresholds.of(5, 15),
                text("platform.automation.retrySpikes.metricLabel", locale),
                text("platform.automation.retrySpikes.metricValue", locale, String.valueOf(disabledLinks)),
                text(disabledLinks > 15
                    ? "platform.automation.retrySpikes.nextAction.critical"
                    : "platform.automation.retrySpikes.nextAction.normal", locale),
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(12)
            ), locale),
            buildReleaseAutomationTask(
                upcomingReleases,
                activeWindows.size(),
                Optional.ofNullable(latestWindowTimestamp).orElseGet(() -> LocalDateTime.now(ZoneOffset.UTC)),
                locale
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
        recordReleaseWindowAudit(saved);
        return mapReleaseWindow(saved);
    }

    /**
     * MVP-c3 — emit a {@link AuditEventType#PLATFORM_REGISTRY_UPDATED}
     * row so the platform-config audit-search tab picks up release-
     * window scheduling. Audit failures must not roll back the
     * release-window write — same posture as
     * {@link com.example.hms.service.impl.RegionPolicyServiceImpl#recordAudit}.
     */
    private void recordReleaseWindowAudit(PlatformReleaseWindow saved) {
        try {
            HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
            String actor = ctx.getPrincipalUsername();
            String description = String.format(
                "Release window scheduled name=%s env=%s starts=%s ends=%s freeze=%s owner=%s",
                saved.getName(), saved.getEnvironment(),
                saved.getStartsAt(), saved.getEndsAt(),
                saved.isFreezeChanges(), saved.getOwnerTeam());
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(ctx.getPrincipalUserId())
                .userName(actor != null && !actor.isBlank() ? actor : SYSTEM_ACTOR)
                .eventType(AuditEventType.PLATFORM_REGISTRY_UPDATED)
                .eventDescription(description)
                .resourceId(saved.getId() == null ? null : saved.getId().toString())
                .resourceName(saved.getName())
                .entityType("PLATFORM_RELEASE_WINDOW")
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.error("[PLATFORM-REGISTRY] Failed to record audit for release window {}",
                saved.getName(), ex);
        }
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public PlatformRegistrySnapshotDTO getRegistrySnapshot() {
        return PlatformRegistrySnapshotDTO.builder()
            .generatedAt(LocalDateTime.now())
            .summary(getRegistrySummary())
            .build();
    }

    private String text(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    /**
     * @param moduleKey message-key prefix; {@code .title} and
     *                  {@code .description} are resolved under it
     */
    private ModuleCardDTO buildModule(String moduleKey, List<OrganizationPlatformService> services,
                                      Predicate<OrganizationPlatformService> filter, Locale locale) {
        List<OrganizationPlatformService> scoped = services.stream().filter(filter).toList();
        long active = scoped.stream().filter(service -> service.getStatus() == PlatformServiceStatus.ACTIVE).count();
        long pending = scoped.stream().filter(service -> service.getStatus() == PlatformServiceStatus.PENDING).count();
        long managed = scoped.stream().filter(OrganizationPlatformService::isManagedByPlatform).count();

        return ModuleCardDTO.builder()
            .title(text(moduleKey + ".title", locale))
            .description(text(moduleKey + ".description", locale))
            .meta(text("platform.module.meta.counts", locale,
                String.valueOf(active), String.valueOf(pending), String.valueOf(managed)))
            .activeIntegrations(active)
            .pendingIntegrations(pending)
            .managedIntegrations(managed)
            .build();
    }

    private AutomationTaskDTO buildAutomationTask(AutomationTaskInput input, Locale locale) {
        AutomationStatus status = evaluateStatus(input.metric(), input.secondaryMetric(), input.thresholds());
        return AutomationTaskDTO.builder()
            .id(input.id())
            .title(input.title())
            .description(input.description())
            .status(status)
            .statusLabel(toStatusLabel(status, locale))
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

    private String toStatusLabel(AutomationStatus status, Locale locale) {
        return text("platform.automation.status." + status.name(), locale);
    }

    private AutomationTaskDTO buildReleaseAutomationTask(long upcomingReleases, long activeWindows,
                                                         LocalDateTime lastRun, Locale locale) {
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
            .title(text("platform.automation.releaseWindows.title", locale))
            .description(text("platform.automation.releaseWindows.description", locale))
            .status(status)
            .statusLabel(toStatusLabel(status, locale))
            .metricLabel(text("platform.automation.releaseWindows.metricLabel", locale))
            .metricValue(text("platform.automation.releaseWindows.metricValue", locale, String.valueOf(upcomingReleases)))
            .nextAction(text(upcomingReleases == 0
                ? "platform.automation.releaseWindows.nextAction.none"
                : "platform.automation.releaseWindows.nextAction.normal", locale))
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
