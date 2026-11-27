package com.example.hms.service.impl;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.IntegrationHealthSnapshotMapper;
import com.example.hms.model.integration.IntegrationHealthSnapshot;
import com.example.hms.payload.dto.superadmin.IntegrationHealthOrgEntryDTO;
import com.example.hms.payload.dto.superadmin.IntegrationHealthRowDTO;
import com.example.hms.payload.dto.superadmin.IntegrationHealthSummaryDTO;
import com.example.hms.repository.IntegrationHealthSnapshotRepository;
import com.example.hms.service.SuperAdminIntegrationHealthService;
import com.example.hms.service.integration.eligibility.EligibilityProvider;
import com.example.hms.service.integration.health.IntegrationHealthActionService;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import com.example.hms.service.platform.discovery.PlatformIntegrationAdapter;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import com.example.hms.service.integration.eligibility.StubEligibilityProvider;
import com.example.hms.service.integration.health.IntegrationDiagnosticText;
import com.example.hms.service.integration.probe.Probe;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;
import java.util.Map;

@Service
public class SuperAdminIntegrationHealthServiceImpl implements SuperAdminIntegrationHealthService {

    private final List<PlatformIntegrationAdapter> platformAdapters;
    private final List<EligibilityProvider> eligibilityProviders;
    private final IntegrationHealthSnapshotRepository snapshotRepository;
    private final IntegrationHealthSnapshotMapper snapshotMapper;
    private final IntegrationHealthActionService actionService;
    private final MessageSource messageSource;

    public SuperAdminIntegrationHealthServiceImpl(
        List<PlatformIntegrationAdapter> platformAdapters,
        List<EligibilityProvider> eligibilityProviders,
        IntegrationHealthSnapshotRepository snapshotRepository,
        IntegrationHealthSnapshotMapper snapshotMapper,
        IntegrationHealthActionService actionService,
        MessageSource messageSource
    ) {
        this.platformAdapters = platformAdapters;
        this.eligibilityProviders = eligibilityProviders;
        this.snapshotRepository = snapshotRepository;
        this.snapshotMapper = snapshotMapper;
        this.actionService = actionService;
        this.messageSource = messageSource;
    }

    @Override
    @Transactional(readOnly = true)
    public IntegrationHealthSummaryDTO getInventory() {
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, List<IntegrationHealthSnapshot>> byIntegration = groupSnapshotsByIntegration();

        List<IntegrationHealthRowDTO> rows = new ArrayList<>();
        for (IntegrationDescriptor descriptor : describeAll(locale)) {
            rows.add(buildRow(descriptor, byIntegration.getOrDefault(descriptor.getId(), List.of())));
        }
        if (hasAnyEligibilityProvider()) {
            rows.add(buildEligibilityRow(byIntegration.getOrDefault(INTEGRATION_ID_ELIGIBILITY, List.of()), locale));
        }
        // QA D4300: a connector that can be tested or re-synchronised is listed,
        // with its history, even when no catalog adapter describes it. Before, the
        // partner connectors answered the actions and were on no screen.
        Set<String> listed = new HashSet<>();
        for (IntegrationHealthRowDTO row : rows) {
            listed.add(row.getIntegrationId());
        }
        for (String connectorId : actionService.registeredIntegrationIds()) {
            if (listed.add(connectorId)) {
                rows.add(buildConnectorRow(connectorId, byIntegration.getOrDefault(connectorId, List.of()), locale));
            }
        }

        return aggregate(rows);
    }

    @Override
    public boolean isListed(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return false;
        }
        if (INTEGRATION_ID_ELIGIBILITY.equals(integrationId)) {
            return hasAnyEligibilityProvider();
        }
        if (descriptorOf(integrationId, LocaleContextHolder.getLocale()) != null) {
            return true;
        }
        return actionService.isKnownIntegration(integrationId);
    }

    @Override
    public Optional<Probe> configurationCheck(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Optional.empty();
        }
        if (INTEGRATION_ID_ELIGIBILITY.equals(integrationId)) {
            if (!hasAnyEligibilityProvider()) {
                return Optional.empty();
            }
            return Optional.of(eligibilityIsStub()
                ? Probe.failed(IntegrationDiagnosticText.NOT_CONNECTED)
                : Probe.ok(0L, IntegrationDiagnosticText.CONFIGURED));
        }
        IntegrationDescriptor descriptor = descriptorOf(integrationId, LocaleContextHolder.getLocale());
        if (descriptor == null) {
            return Optional.empty();
        }
        if (descriptor.isStub()) {
            return Optional.of(Probe.failed(IntegrationDiagnosticText.NOT_CONNECTED));
        }
        if (!descriptor.isEnabled()) {
            return Optional.of(Probe.failed(IntegrationDiagnosticText.DISABLED));
        }
        return Optional.of(Probe.ok(0L, IntegrationDiagnosticText.CONFIGURED));
    }

    private IntegrationDescriptor descriptorOf(String integrationId, Locale locale) {
        for (PlatformIntegrationAdapter adapter : platformAdapters) {
            IntegrationDescriptor descriptor = adapter.describe(locale);
            if (descriptor != null && integrationId.equals(descriptor.getId())) {
                return descriptor;
            }
        }
        return null;
    }

    /** Every eligibility provider is the built-in reference one: no insurer is connected. */
    private boolean eligibilityIsStub() {
        if (eligibilityProviders == null) {
            return true;
        }
        for (EligibilityProvider provider : eligibilityProviders) {
            if (!(provider instanceof StubEligibilityProvider)) {
                return false;
            }
        }
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public IntegrationHealthRowDTO getIntegration(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            throw new ResourceNotFoundException("integration.health.notfound", "");
        }
        Locale locale = LocaleContextHolder.getLocale();
        if (INTEGRATION_ID_ELIGIBILITY.equalsIgnoreCase(integrationId) && hasAnyEligibilityProvider()) {
            List<IntegrationHealthSnapshot> snapshots =
                snapshotRepository.findByIntegrationIdOrderByLastStatusAscUpdatedAtDesc(INTEGRATION_ID_ELIGIBILITY);
            return buildEligibilityRow(snapshots, locale);
        }
        for (PlatformIntegrationAdapter adapter : platformAdapters) {
            IntegrationDescriptor descriptor = adapter.describe(locale);
            if (descriptor != null && integrationId.equalsIgnoreCase(descriptor.getId())) {
                List<IntegrationHealthSnapshot> snapshots =
                    snapshotRepository.findByIntegrationIdOrderByLastStatusAscUpdatedAtDesc(descriptor.getId());
                return buildRow(descriptor, snapshots);
            }
        }
        if (actionService.isKnownIntegration(integrationId)) {
            return buildConnectorRow(integrationId,
                snapshotRepository.findByIntegrationIdOrderByLastStatusAscUpdatedAtDesc(integrationId), locale);
        }
        throw new ResourceNotFoundException("integration.health.notfound", integrationId);
    }

    /**
     * QA D4300: a registered connector with no catalog entry. Its name comes from
     * the bundle ({@code integration.connector.<id>.displayName}); a connector the
     * bundle does not know is listed under its identifier rather than hidden.
     */
    private IntegrationHealthRowDTO buildConnectorRow(String connectorId,
                                                      List<IntegrationHealthSnapshot> snapshots,
                                                      Locale locale) {
        List<IntegrationHealthOrgEntryDTO> orgEntries = mapEntries(snapshots);
        String name = messageSource.getMessage(
            "integration.connector." + connectorId + ".displayName", null, connectorId, locale);
        return IntegrationHealthRowDTO.builder()
            .integrationId(connectorId)
            .displayName(name)
            .serviceType(null)
            .provider(text("integration.connector.provider", locale))
            .enabled(true)
            .stub(actionService.isStub(connectorId))
            .probeSupported(actionService.supportsProbe(connectorId))
            .resyncSupported(actionService.supportsResync(connectorId))
            .capabilities(List.of())
            .rolledUpStatus(rollUp(orgEntries))
            .organizations(orgEntries)
            .build();
    }

    private Map<String, List<IntegrationHealthSnapshot>> groupSnapshotsByIntegration() {
        Map<String, List<IntegrationHealthSnapshot>> bucket = new LinkedHashMap<>();
        for (IntegrationHealthSnapshot snapshot : snapshotRepository.findAllByOrderByIntegrationIdAscLastStatusAsc()) {
            bucket.computeIfAbsent(snapshot.getIntegrationId(), k -> new ArrayList<>()).add(snapshot);
        }
        return bucket;
    }

    private List<IntegrationDescriptor> describeAll(Locale locale) {
        List<IntegrationDescriptor> descriptors = new ArrayList<>();
        for (PlatformIntegrationAdapter adapter : platformAdapters) {
            IntegrationDescriptor descriptor = adapter.describe(locale);
            if (descriptor != null) {
                descriptors.add(descriptor);
            }
        }
        descriptors.sort(Comparator.comparing(IntegrationDescriptor::getId, Comparator.nullsLast(String::compareTo)));
        return descriptors;
    }

    private boolean hasAnyEligibilityProvider() {
        return eligibilityProviders != null && !eligibilityProviders.isEmpty();
    }

    private IntegrationHealthRowDTO buildRow(IntegrationDescriptor descriptor,
                                             List<IntegrationHealthSnapshot> snapshots) {
        List<IntegrationHealthOrgEntryDTO> orgEntries = mapEntries(snapshots);
        return IntegrationHealthRowDTO.builder()
            .integrationId(descriptor.getId())
            .displayName(descriptor.getDisplayName())
            .serviceType(descriptor.getServiceType())
            .provider(descriptor.getProvider())
            .enabled(descriptor.isEnabled())
            .stub(descriptor.isStub())
            // QA D4300: every listed integration can be tested - by its probe, or by
            // the configuration check when it has none.
            .probeSupported(true)
            .resyncSupported(actionService.supportsResync(descriptor.getId()))
            .capabilities(descriptor.getCapabilities() == null ? List.of() : descriptor.getCapabilities())
            .rolledUpStatus(rollUp(orgEntries))
            .organizations(orgEntries)
            .build();
    }

    /**
     * QA D874: the eligibility row is described in the caller's language, like
     * the adapter rows, and names its provider in words rather than by the
     * Java class that implements it.
     */
    private IntegrationHealthRowDTO buildEligibilityRow(List<IntegrationHealthSnapshot> snapshots,
                                                        Locale locale) {
        List<IntegrationHealthOrgEntryDTO> orgEntries = mapEntries(snapshots);
        return IntegrationHealthRowDTO.builder()
            .integrationId(INTEGRATION_ID_ELIGIBILITY)
            .displayName(text("integration.eligibility.displayName", locale))
            .serviceType(null)
            .provider(text("integration.eligibility.provider", locale))
            .enabled(true)
            .probeSupported(true)
            .resyncSupported(actionService.supportsResync(INTEGRATION_ID_ELIGIBILITY))
            .capabilities(List.of(
                text("integration.eligibility.capability.coverage", locale),
                text("integration.eligibility.capability.priorAuth", locale)))
            .rolledUpStatus(rollUp(orgEntries))
            .organizations(orgEntries)
            .build();
    }

    private String text(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }

    private List<IntegrationHealthOrgEntryDTO> mapEntries(List<IntegrationHealthSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        List<IntegrationHealthOrgEntryDTO> entries = new ArrayList<>(snapshots.size());
        for (IntegrationHealthSnapshot snapshot : snapshots) {
            entries.add(snapshotMapper.toDto(snapshot));
        }
        return entries;
    }

    /**
     * Worst-case status: FAILING > DEGRADED > HEALTHY > NO_HISTORY. An empty
     * org list rolls up to NO_HISTORY (the integration is registered but has
     * never been called).
     */
    private IntegrationHealthStatus rollUp(Collection<IntegrationHealthOrgEntryDTO> entries) {
        if (entries == null || entries.isEmpty()) {
            return IntegrationHealthStatus.NO_HISTORY;
        }
        IntegrationHealthStatus worst = IntegrationHealthStatus.NO_HISTORY;
        for (IntegrationHealthOrgEntryDTO entry : entries) {
            IntegrationHealthStatus status = entry.getStatus();
            if (status == IntegrationHealthStatus.FAILING) {
                return IntegrationHealthStatus.FAILING;
            }
            if (status == IntegrationHealthStatus.DEGRADED) {
                worst = IntegrationHealthStatus.DEGRADED;
            } else if (status == IntegrationHealthStatus.HEALTHY && worst != IntegrationHealthStatus.DEGRADED) {
                worst = IntegrationHealthStatus.HEALTHY;
            }
        }
        return worst;
    }

    private IntegrationHealthSummaryDTO aggregate(List<IntegrationHealthRowDTO> rows) {
        int healthy = 0;
        int degraded = 0;
        int failing = 0;
        int noHistory = 0;
        for (IntegrationHealthRowDTO row : rows) {
            switch (row.getRolledUpStatus()) {
                case HEALTHY -> healthy++;
                case DEGRADED -> degraded++;
                case FAILING -> failing++;
                case NO_HISTORY -> noHistory++;
            }
        }
        return IntegrationHealthSummaryDTO.builder()
            .totalIntegrations(rows.size())
            .healthyCount(healthy)
            .degradedCount(degraded)
            .failingCount(failing)
            .noHistoryCount(noHistory)
            .integrations(rows)
            .build();
    }
}
