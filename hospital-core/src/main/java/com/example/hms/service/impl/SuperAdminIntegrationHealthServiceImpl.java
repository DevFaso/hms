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
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import com.example.hms.service.platform.discovery.PlatformIntegrationAdapter;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SuperAdminIntegrationHealthServiceImpl implements SuperAdminIntegrationHealthService {

    private final List<PlatformIntegrationAdapter> platformAdapters;
    private final List<EligibilityProvider> eligibilityProviders;
    private final IntegrationHealthSnapshotRepository snapshotRepository;
    private final IntegrationHealthSnapshotMapper snapshotMapper;

    public SuperAdminIntegrationHealthServiceImpl(
        List<PlatformIntegrationAdapter> platformAdapters,
        List<EligibilityProvider> eligibilityProviders,
        IntegrationHealthSnapshotRepository snapshotRepository,
        IntegrationHealthSnapshotMapper snapshotMapper
    ) {
        this.platformAdapters = platformAdapters;
        this.eligibilityProviders = eligibilityProviders;
        this.snapshotRepository = snapshotRepository;
        this.snapshotMapper = snapshotMapper;
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
            rows.add(buildEligibilityRow(byIntegration.getOrDefault(INTEGRATION_ID_ELIGIBILITY, List.of())));
        }

        return aggregate(rows);
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
            return buildEligibilityRow(snapshots);
        }
        for (PlatformIntegrationAdapter adapter : platformAdapters) {
            IntegrationDescriptor descriptor = adapter.describe(locale);
            if (descriptor != null && integrationId.equalsIgnoreCase(descriptor.getId())) {
                List<IntegrationHealthSnapshot> snapshots =
                    snapshotRepository.findByIntegrationIdOrderByLastStatusAscUpdatedAtDesc(descriptor.getId());
                return buildRow(descriptor, snapshots);
            }
        }
        throw new ResourceNotFoundException("integration.health.notfound", integrationId);
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
            .capabilities(descriptor.getCapabilities() == null ? List.of() : descriptor.getCapabilities())
            .rolledUpStatus(rollUp(orgEntries))
            .organizations(orgEntries)
            .build();
    }

    private IntegrationHealthRowDTO buildEligibilityRow(List<IntegrationHealthSnapshot> snapshots) {
        List<IntegrationHealthOrgEntryDTO> orgEntries = mapEntries(snapshots);
        String provider = eligibilityProviders.stream()
            .findFirst()
            .map(p -> p.getClass().getSimpleName())
            .orElse("Eligibility provider");
        return IntegrationHealthRowDTO.builder()
            .integrationId(INTEGRATION_ID_ELIGIBILITY)
            .displayName("Insurance eligibility & prior-auth")
            .serviceType(null)
            .provider(provider)
            .enabled(true)
            .capabilities(List.of(
                "Coverage check (X12 270/271 analogue)",
                "Prior-auth submission (X12 278 analogue)"))
            .rolledUpStatus(rollUp(orgEntries))
            .organizations(orgEntries)
            .build();
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
