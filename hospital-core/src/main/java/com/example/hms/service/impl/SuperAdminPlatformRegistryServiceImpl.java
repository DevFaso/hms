package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.platform.PlatformReleaseStatus;
import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
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
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SuperAdminPlatformRegistryServiceImpl implements SuperAdminPlatformRegistryService {

    private static final String SYSTEM_ACTOR = "system";

    /** Default and ceiling for the release-window list (D6). */
    static final int DEFAULT_RELEASE_WINDOW_LIMIT = 50;
    static final int MAX_RELEASE_WINDOW_LIMIT = 200;

    /*
     * D8 — the module cards partition the service types, so every service is
     * counted in exactly one card and each card counts what its title says.
     * Before, "Communications" counted ANALYTICS and "Terminology packs"
     * counted INVENTORY (there is no terminology service type at all); the
     * third card is now named for what it holds.
     */
    private static final Set<PlatformServiceType> CLINICAL_TYPES = EnumSet.of(
        PlatformServiceType.EHR,
        PlatformServiceType.LIMS,
        PlatformServiceType.ORTHO_IMAGING,
        PlatformServiceType.REMOTE_MONITORING,
        PlatformServiceType.RESP_TELEMED,
        PlatformServiceType.CLINICAL_ANALYTICS);
    private static final Set<PlatformServiceType> COMMUNICATION_TYPES = EnumSet.of(
        PlatformServiceType.PEDIATRIC_MESSAGING);
    private static final Set<PlatformServiceType> OPERATIONS_TYPES = EnumSet.of(
        PlatformServiceType.BILLING,
        PlatformServiceType.INVENTORY,
        PlatformServiceType.ANALYTICS);

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

        LocalDateTime now = LocalDateTime.now();
        long unreadNotifications = notificationRepository.countByReadFalse();
        long staleNotifications = notificationRepository.countByReadFalseAndCreatedAtBefore(now.minusHours(4));

        // D7: derived from the window's times at read, not from the status
        // stamped at creation (which never advanced, so "active" only grew).
        long activeWindows = platformReleaseWindowRepository
            .countByStatusNotAndEndsAtAfter(PlatformReleaseStatus.CANCELLED, now);
        long upcomingWindows = platformReleaseWindowRepository
            .countByStatusNotAndStartsAtAfter(PlatformReleaseStatus.CANCELLED, now);
        LocalDateTime lastReleaseWindowChange = platformReleaseWindowRepository.findFirstByOrderByUpdatedAtDesc()
            .map(window -> Optional.ofNullable(window.getUpdatedAt()).orElse(window.getCreatedAt()))
            .orElse(null);

        // Every string below is read by the super-admin who requested the
        // summary, so the request locale applies.
        Locale locale = LocaleContextHolder.getLocale();

        ModuleCardDTO clinical = buildModule("platform.module.clinical", services, CLINICAL_TYPES, locale);
        ModuleCardDTO communications = buildModule("platform.module.communications", services, COMMUNICATION_TYPES, locale);
        ModuleCardDTO operations = buildModule("platform.module.operations", services, OPERATIONS_TYPES, locale);

        // D8: each task reports a metric this system really holds, under a
        // name that says what it is. None of them is a job that runs, so none
        // carries a "last run" time (the old values were now-5min / now-12min).
        List<AutomationTaskDTO> automation = List.of(
            buildAutomationTask(new AutomationTaskInput(
                "unread-notifications",
                text("platform.automation.unreadNotifications.title", locale),
                text("platform.automation.unreadNotifications.description", locale),
                unreadNotifications,
                staleNotifications,
                Thresholds.of(10, 30),
                text("platform.automation.unreadNotifications.metricLabel", locale),
                text("platform.automation.unreadNotifications.metricValue", locale,
                    String.valueOf(unreadNotifications), String.valueOf(staleNotifications)),
                text(unreadNotifications > 30
                    ? "platform.automation.unreadNotifications.nextAction.critical"
                    : "platform.automation.unreadNotifications.nextAction.normal", locale)
            ), locale),
            buildAutomationTask(new AutomationTaskInput(
                "disabled-links",
                text("platform.automation.disabledLinks.title", locale),
                text("platform.automation.disabledLinks.description", locale),
                disabledLinks,
                disabledLinks,
                Thresholds.of(5, 15),
                text("platform.automation.disabledLinks.metricLabel", locale),
                text("platform.automation.disabledLinks.metricValue", locale, String.valueOf(disabledLinks)),
                text(disabledLinks > 15
                    ? "platform.automation.disabledLinks.nextAction.critical"
                    : "platform.automation.disabledLinks.nextAction.normal", locale)
            ), locale),
            buildReleaseAutomationTask(upcomingWindows, activeWindows, locale)
        );

        ActionPanelDTO actions = ActionPanelDTO.builder()
            .totalIntegrations(services.size())
            .pendingIntegrations(services.stream().filter(s -> s.getStatus() == PlatformServiceStatus.PENDING).count())
            .disabledLinks(disabledLinks)
            .activeReleaseWindows(activeWindows)
            .lastReleaseWindowChangeAt(lastReleaseWindowChange)
            .build();

        return SuperAdminPlatformRegistrySummaryDTO.builder()
            .modules(List.of(clinical, communications, operations))
            .automationTasks(automation)
            .actions(actions)
            .build();
    }

    @Override
    public PlatformReleaseWindowResponseDTO scheduleReleaseWindow(PlatformReleaseWindowRequestDTO request) {
        if (!request.getEndsAt().isAfter(request.getStartsAt())) {
            throw new BusinessException("platform.releaseWindow.endBeforeStart");
        }
        // D7b: the unique (name, environment) constraint used to fire at
        // flush, after the audit row had already committed in its own
        // transaction — a refused schedule left an audit entry behind.
        if (platformReleaseWindowRepository.existsByNameAndEnvironment(request.getName(), request.getEnvironment())) {
            // No arguments: ConflictException's handler splits a message on its
            // first ':' (a "field:" prefix), and a window name may contain one.
            throw new ConflictException(MessageUtil.resolve("platform.releaseWindow.duplicate"));
        }

        PlatformReleaseWindow releaseWindow = PlatformReleaseWindow.builder()
            .name(request.getName())
            .description(request.getDescription())
            .environment(request.getEnvironment())
            .startsAt(request.getStartsAt())
            .endsAt(request.getEndsAt())
            .status(statusAt(PlatformReleaseStatus.SCHEDULED, request.getStartsAt(), request.getEndsAt(), LocalDateTime.now()))
            .freezeChanges(request.isFreezeChanges())
            .ownerTeam(request.getOwnerTeam())
            .notes(request.getNotes())
            .build();

        // Flushed here so a constraint the check above did not see (a
        // concurrent insert) fails this call, before any audit is scheduled.
        PlatformReleaseWindow saved = platformReleaseWindowRepository.saveAndFlush(releaseWindow);
        recordReleaseWindowAudit(saved);
        return mapReleaseWindow(saved, LocalDateTime.now());
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<PlatformReleaseWindowResponseDTO> listReleaseWindows(Integer limit) {
        int size = limit == null || limit < 1
            ? DEFAULT_RELEASE_WINDOW_LIMIT
            : Math.min(limit, MAX_RELEASE_WINDOW_LIMIT);
        LocalDateTime now = LocalDateTime.now();
        // Newest start first: upcoming windows lead, the oldest history drops
        // off the end of the capped page.
        return platformReleaseWindowRepository
            .findAll(PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "startsAt")))
            .stream()
            .map(window -> mapReleaseWindow(window, now))
            .toList();
    }

    /**
     * MVP-c3 — emit a {@link AuditEventType#PLATFORM_REGISTRY_UPDATED}
     * row so the platform-config audit-search tab picks up release-
     * window scheduling. After commit (D7b): {@code logEvent} writes in its
     * own transaction, so recording it inline kept the row even when this
     * one rolled back. Audit failures never fail the schedule — same posture
     * as {@link com.example.hms.service.impl.RegionPolicyServiceImpl#recordAudit}.
     */
    private void recordReleaseWindowAudit(PlatformReleaseWindow saved) {
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        String actor = ctx.getPrincipalUsername();
        String description = String.format(
            "Release window scheduled name=%s env=%s starts=%s ends=%s freeze=%s owner=%s",
            saved.getName(), saved.getEnvironment(),
            saved.getStartsAt(), saved.getEndsAt(),
            saved.isFreezeChanges(), saved.getOwnerTeam());
        AuditEventRequestDTO event = AuditEventRequestDTO.builder()
            .userId(ctx.getPrincipalUserId())
            .userName(actor != null && !actor.isBlank() ? actor : SYSTEM_ACTOR)
            .eventType(AuditEventType.PLATFORM_REGISTRY_UPDATED)
            .eventDescription(description)
            .resourceId(saved.getId() == null ? null : saved.getId().toString())
            .resourceName(saved.getName())
            .entityType("PLATFORM_RELEASE_WINDOW")
            .status(AuditStatus.SUCCESS)
            .build();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(event);
            } catch (RuntimeException ex) {
                log.error("[PLATFORM-REGISTRY] Failed to record audit for release window {}",
                    saved.getName(), ex);
            }
        });
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
                                      Set<PlatformServiceType> types, Locale locale) {
        List<OrganizationPlatformService> scoped = services.stream()
            .filter(service -> types.contains(service.getServiceType()))
            .toList();
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

    private AutomationTaskDTO buildReleaseAutomationTask(long upcomingWindows, long activeWindows, Locale locale) {
        AutomationStatus status;
        if (activeWindows == 0) {
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
            .metricValue(text("platform.automation.releaseWindows.metricValue", locale,
                String.valueOf(upcomingWindows), String.valueOf(activeWindows)))
            .nextAction(text(activeWindows == 0
                ? "platform.automation.releaseWindows.nextAction.none"
                : "platform.automation.releaseWindows.nextAction.normal", locale))
            .build();
    }

    /**
     * D7 — a window's status at {@code now}: CANCELLED is a decision and
     * stays; otherwise it follows the clock (SCHEDULED before the start,
     * IN_PROGRESS between start and end inclusive, COMPLETED after the end).
     */
    static PlatformReleaseStatus statusAt(PlatformReleaseStatus stored,
                                          LocalDateTime startsAt,
                                          LocalDateTime endsAt,
                                          LocalDateTime now) {
        if (stored == PlatformReleaseStatus.CANCELLED) {
            return PlatformReleaseStatus.CANCELLED;
        }
        if (now.isAfter(endsAt)) {
            return PlatformReleaseStatus.COMPLETED;
        }
        if (now.isBefore(startsAt)) {
            return PlatformReleaseStatus.SCHEDULED;
        }
        return PlatformReleaseStatus.IN_PROGRESS;
    }

    private PlatformReleaseWindowResponseDTO mapReleaseWindow(PlatformReleaseWindow window, LocalDateTime now) {
        return PlatformReleaseWindowResponseDTO.builder()
            .id(window.getId())
            .name(window.getName())
            .description(window.getDescription())
            .environment(window.getEnvironment())
            .startsAt(window.getStartsAt())
            .endsAt(window.getEndsAt())
            .status(statusAt(window.getStatus(), window.getStartsAt(), window.getEndsAt(), now))
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
        String nextAction
    ) {}
}
