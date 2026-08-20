package com.example.hms.service.impl;

import com.example.hms.config.FeatureFlagProperties;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.platform.FeatureFlagOverride;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.featureflag.FeatureFlagOverrideResponseDTO;
import com.example.hms.repository.platform.FeatureFlagOverrideRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.FeatureFlagService;
import com.example.hms.service.SubscriptionFeatureGateService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagServiceImpl implements FeatureFlagService {

    private final FeatureFlagProperties properties;
    private final Environment environment;
    private final FeatureFlagOverrideRepository overrideRepository;
    private final SubscriptionFeatureGateService subscriptionFeatureGate;
    private final AuditEventLogService auditEventLogService;

    /**
     * MVP-6c plan-tier audit dedup window. Keyed on
     * {@code orgId + ":" + flagKey}; value is the last-emit timestamp.
     * In-memory + per-instance — flag resolution is hot, so this prevents
     * a tight loop from flooding the audit log; cross-instance dedup is
     * out of scope (different instances seeing different cache windows
     * is acceptable and self-correcting).
     *
     * <p>Bounded so a long-lived node with many tenants × flag keys
     * cannot leak memory (Copilot review fix — the prior
     * ConcurrentHashMap had no eviction). On every successful emit we
     * sweep entries older than 2× the window; if the map exceeds the
     * hard cap, drop the oldest entries down to half the cap. Both
     * paths run inside the rate-limited emit branch so the per-call
     * cost is bounded.
     */
    private static final long PLAN_GATE_AUDIT_DEDUP_MILLIS = 5L * 60L * 1000L;
    private static final int PLAN_GATE_AUDIT_DEDUP_MAX_SIZE = 10_000;
    private final ConcurrentMap<String, Long> planGateAuditDedup = new ConcurrentHashMap<>();

    @Override
    public Map<String, Boolean> listFlags(String environmentOverride, Locale locale) {
        return resolveEffectiveFlags(environmentOverride, locale);
    }

    @Override
    @Transactional
    public Map<String, Boolean> upsertOverride(
        String flagKey,
        boolean enabled,
        String description,
        String updatedBy,
        String environmentOverride,
        Locale locale
    ) {
        // Legacy global-only entry point (kept for EmergencyControlService.killFeature
        // and any other call-site that pre-dates MVP-7b). Delegates to the
        // shared private helper to avoid a self-call that would bypass the
        // @Transactional proxy (Sonar S6809).
        return doUpsert(flagKey, enabled, description, updatedBy,
            environmentOverride, /* organizationId */ null, locale);
    }

    @Override
    @Transactional
    public Map<String, Boolean> upsertOverride(
        String flagKey,
        boolean enabled,
        String description,
        String updatedBy,
        String environmentOverride,
        UUID organizationId,
        Locale locale
    ) {
        return doUpsert(flagKey, enabled, description, updatedBy,
            environmentOverride, organizationId, locale);
    }

    private Map<String, Boolean> doUpsert(
        String flagKey,
        boolean enabled,
        String description,
        String updatedBy,
        String environmentOverride,
        UUID organizationId,
        Locale locale
    ) {
        String normalizedKey = normalizeKey(flagKey);
        FeatureFlagOverride override = overrideRepository
            .findByFlagKeyAndOrganizationId(normalizedKey, organizationId)
            .orElseGet(() -> FeatureFlagOverride.builder()
                .flagKey(normalizedKey)
                .organizationId(organizationId)
                .build());
        Boolean previousEnabled = override.getId() == null ? null : override.isEnabled();
        override.setEnabled(enabled);
        override.setDescription(sanitizeDescription(description));
        override.setUpdatedBy(updatedBy);
        overrideRepository.save(override);
        log.info(
            "Feature flag override saved key={} enabled={} updatedBy={} env={} org={}",
            normalizedKey,
            enabled,
            updatedBy,
            environmentOverride,
            organizationId
        );
        emitConfigurationChangedAudit(
            "FEATURE_FLAG_OVERRIDE_" + (previousEnabled == null ? "CREATED" : "UPDATED"),
            String.format(
                "Feature flag override %s key=%s enabled=%s%s%s previousEnabled=%s",
                previousEnabled == null ? "created" : "updated",
                normalizedKey, enabled,
                organizationId == null ? "" : " org=" + organizationId,
                environmentOverride == null ? "" : " env=" + environmentOverride,
                previousEnabled),
            normalizedKey,
            updatedBy);
        return resolveEffectiveFlags(environmentOverride, locale);
    }

    @Override
    @Transactional
    public Map<String, Boolean> deleteOverride(
        String flagKey,
        String updatedBy,
        String environmentOverride,
        Locale locale
    ) {
        // Legacy global-only entry point.
        return doDelete(flagKey, updatedBy, environmentOverride,
            /* organizationId */ null, locale);
    }

    @Override
    @Transactional
    public Map<String, Boolean> deleteOverride(
        String flagKey,
        String updatedBy,
        String environmentOverride,
        UUID organizationId,
        Locale locale
    ) {
        return doDelete(flagKey, updatedBy, environmentOverride, organizationId, locale);
    }

    private Map<String, Boolean> doDelete(
        String flagKey,
        String updatedBy,
        String environmentOverride,
        UUID organizationId,
        Locale locale
    ) {
        String normalizedKey = normalizeKey(flagKey);
        overrideRepository.findByFlagKeyAndOrganizationId(normalizedKey, organizationId)
            .ifPresent(entity -> {
                overrideRepository.delete(entity);
                log.info("Feature flag override removed key={} updatedBy={} env={} org={} id={}",
                    normalizedKey, updatedBy, environmentOverride, organizationId, entity.getId());
                emitConfigurationChangedAudit(
                    "FEATURE_FLAG_OVERRIDE_DELETED",
                    String.format(
                        "Feature flag override deleted key=%s%s%s previousEnabled=%s",
                        normalizedKey,
                        organizationId == null ? "" : " org=" + organizationId,
                        environmentOverride == null ? "" : " env=" + environmentOverride,
                        entity.isEnabled()),
                    normalizedKey,
                    updatedBy);
            });
        return resolveEffectiveFlags(environmentOverride, locale);
    }

    /**
     * MVP-c3 — emit a {@link AuditEventType#CONFIGURATION_CHANGED}
     * row so the platform-config audit-search tab picks up
     * feature-flag writes. Audit failures are swallowed: the operator's
     * write must succeed even if the audit pipeline is unhealthy
     * (same posture as
     * {@link com.example.hms.service.impl.RegionPolicyServiceImpl#recordAudit}).
     */
    private void emitConfigurationChangedAudit(
        String resourceName,
        String description,
        String resourceId,
        String updatedBy
    ) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(currentActorId())
                .userName(updatedBy != null && !updatedBy.isBlank() ? updatedBy : "system")
                .eventType(AuditEventType.CONFIGURATION_CHANGED)
                .eventDescription(description)
                .resourceId(resourceId)
                .resourceName(resourceName)
                .entityType("FEATURE_FLAG_OVERRIDE")
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.error("[FEATURE-FLAG] Failed to record audit for {}", resourceId, ex);
        }
    }

    private UUID currentActorId() {
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        return ctx.getPrincipalUserId();
    }

    private Map<String, Boolean> resolveEffectiveFlags(String environmentOverride, Locale locale) {
        String resolvedEnvironment = resolveEnvironment(environmentOverride);
        UUID callerOrganizationId = currentOrganizationId();
        Map<String, Boolean> flags = new LinkedHashMap<>();
        merge(flags, properties.getDefaultsOrEmpty());
        merge(flags, properties.getOverridesOrEmpty());
        merge(flags, properties.getEnvironmentOverrides(resolvedEnvironment));
        mergeGlobalDatabaseOverrides(flags);
        // MVP-7b — per-tenant overrides win over the global override for
        // the caller's organization only. Skipped for system / super-admin
        // contexts (currentOrganizationId() returns null in both cases).
        if (callerOrganizationId != null) {
            mergeTenantDatabaseOverrides(flags, callerOrganizationId);
        }
        applySubscriptionPlanGate(flags, callerOrganizationId);
        log.debug("Resolved feature flags for env='{}' org='{}' locale='{}' -> {}",
            resolvedEnvironment, callerOrganizationId, locale, flags);
        return flags;
    }

    /**
     * MVP-6b: enforce the active subscription plan's {@code featureKeys}
     * for the caller's organization. Flags whose key is not in the
     * plan's allowed list get forced to {@code false}; everything else
     * passes through unchanged. Orgs without an active subscription —
     * and callers without a tenant context (system / super-admin
     * cross-tenant) — are ungated, matching the legacy behaviour.
     *
     * <p>MVP-6c: every flip from {@code true → false} emits a
     * {@link AuditEventType#PLAN_FEATURE_GATE_BLOCKED} audit event,
     * dedup'd per {@code (org, flag)} on a 5-min window so a hot
     * resolve loop can't flood the log. Audit failures are swallowed
     * so flag resolution never breaks because of audit pressure.
     */
    private void applySubscriptionPlanGate(Map<String, Boolean> flags, UUID organizationId) {
        if (flags.isEmpty() || organizationId == null) {
            return;
        }
        flags.replaceAll((key, value) -> {
            if (!Boolean.TRUE.equals(value)) {
                // Already off — no need to ask the gate (and a "false"
                // doesn't need plan permission to stay false).
                return value;
            }
            boolean allowed = subscriptionFeatureGate.isFeatureAllowedForOrg(organizationId, key);
            if (!allowed) {
                emitPlanGateBlockedAudit(organizationId, key);
            }
            return allowed;
        });
    }

    private void emitPlanGateBlockedAudit(UUID organizationId, String flagKey) {
        String dedupKey = organizationId + ":" + flagKey;
        long now = System.currentTimeMillis();
        Long previous = planGateAuditDedup.get(dedupKey);
        if (previous != null && now - previous < PLAN_GATE_AUDIT_DEDUP_MILLIS) {
            return;
        }
        // Use compute to win the race with another thread that just sent
        // an event for the same key — the second caller sees the updated
        // timestamp and short-circuits next time.
        boolean shouldEmit = planGateAuditDedup.compute(dedupKey, (k, prev) -> {
            if (prev != null && now - prev < PLAN_GATE_AUDIT_DEDUP_MILLIS) {
                return prev;
            }
            return now;
        }) == now;
        if (!shouldEmit) {
            return;
        }
        // Bounded eviction (Copilot review fix) — every emission sweeps
        // expired entries; if the map is still above the hard cap, drop
        // the oldest half. Cost is amortised across emissions, which are
        // themselves rate-limited, so this never runs on every flag
        // resolution.
        evictExpiredAuditDedupEntries(now);
        try {
            HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(ctx.getPrincipalUserId())
                .userName(ctx.getPrincipalUsername())
                .eventType(AuditEventType.PLAN_FEATURE_GATE_BLOCKED)
                .eventDescription("Plan-tier gate blocked flag '" + flagKey
                    + "' for organization " + organizationId)
                .resourceId(flagKey)
                .resourceName(flagKey)
                .entityType("FEATURE_FLAG")
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            // Audit pressure must never break flag resolution.
            log.warn("[FEATURE-FLAGS] Plan-gate audit emit failed for org={} flag={}: {}",
                organizationId, flagKey, ex.getMessage());
        }
    }

    /**
     * Drop entries whose last-emit timestamp is older than 2× the dedup
     * window (no longer participates in dedup decisions); if the map is
     * still over the hard cap after that, evict the oldest entries until
     * we're at half capacity. Visible for testing.
     */
    void evictExpiredAuditDedupEntries(long now) {
        long staleCutoff = now - (2L * PLAN_GATE_AUDIT_DEDUP_MILLIS);
        planGateAuditDedup.entrySet().removeIf(e -> e.getValue() < staleCutoff);

        int size = planGateAuditDedup.size();
        if (size <= PLAN_GATE_AUDIT_DEDUP_MAX_SIZE) {
            return;
        }
        // Fall back to oldest-first eviction. Sorting a 10k-entry map
        // is acceptable here because this branch only runs when an emit
        // was about to be made AND the map exceeded the cap — i.e.,
        // very rarely on a healthy node.
        int target = PLAN_GATE_AUDIT_DEDUP_MAX_SIZE / 2;
        planGateAuditDedup.entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByValue())
            .limit((long) size - target)
            .map(java.util.Map.Entry::getKey)
            .toList()
            .forEach(planGateAuditDedup::remove);
    }

    /** Visible for testing — exposes the live dedup map size. */
    int planGateAuditDedupSize() {
        return planGateAuditDedup.size();
    }

    private UUID currentOrganizationId() {
        try {
            HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
            // Super-admin requests carry no tenant context (they operate
            // cross-tenant by design) — leave them ungated.
            if (ctx == null || ctx.isSuperAdmin()) {
                return null;
            }
            return ctx.getActiveOrganizationId();
        } catch (RuntimeException ex) {
            // Holder lookup must never break flag resolution. Stay
            // ungated if anything goes wrong.
            log.debug("[FEATURE-FLAGS] currentOrganizationId() lookup failed: {}", ex.getMessage());
            return null;
        }
    }

    private void merge(Map<String, Boolean> target, Map<String, Boolean> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        source.forEach((key, value) -> {
            if (!StringUtils.hasText(key)) {
                return;
            }
            String normalizedKey = key.trim();
            target.put(normalizedKey, Boolean.TRUE.equals(value));
        });
    }

    /**
     * MVP-7b: layer the *global* DB overrides (rows with
     * {@code organization_id} NULL) on top of the property-driven map.
     * Per-tenant rows are applied separately by
     * {@link #mergeTenantDatabaseOverrides} so a tenant override wins
     * for that tenant only.
     */
    private void mergeGlobalDatabaseOverrides(Map<String, Boolean> target) {
        overrideRepository.findAllByOrderByFlagKeyAsc().forEach(override -> {
            if (override.getOrganizationId() != null) {
                // Per-tenant row — handled by the next merge step when the
                // caller belongs to that organization.
                return;
            }
            String key = override.getFlagKey();
            if (!StringUtils.hasText(key)) {
                return;
            }
            target.put(key.trim(), override.isEnabled());
        });
    }

    /**
     * MVP-7b: layer the per-tenant DB overrides for the caller's
     * organization on top of the global merge. Skipped entirely for
     * system / super-admin callers ({@link #resolveEffectiveFlags}
     * passes {@code null} and short-circuits before calling this).
     */
    private void mergeTenantDatabaseOverrides(Map<String, Boolean> target, UUID organizationId) {
        overrideRepository.findByOrganizationIdOrderByFlagKeyAsc(organizationId)
            .forEach(override -> {
                String key = override.getFlagKey();
                if (!StringUtils.hasText(key)) {
                    return;
                }
                target.put(key.trim(), override.isEnabled());
            });
    }

    private String resolveEnvironment(String requestedEnvironment) {
        if (StringUtils.hasText(requestedEnvironment)) {
            return requestedEnvironment.trim();
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length > 0) {
            for (String profile : activeProfiles) {
                if (StringUtils.hasText(profile)) {
                    return profile.trim();
                }
            }
        }
        String defaultProfile = environment.getProperty("spring.profiles.default");
        if (StringUtils.hasText(defaultProfile)) {
            return Objects.requireNonNull(defaultProfile).trim();
        }
        return "default";
    }

    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("Feature flag key must not be blank");
        }
        return key.trim();
    }

    private String sanitizeDescription(String description) {
        if (!StringUtils.hasText(description)) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeatureFlagOverrideResponseDTO> listOverrides() {
        return overrideRepository.findAllByOrderByFlagKeyAsc().stream()
            .map(o -> FeatureFlagOverrideResponseDTO.builder()
                .id(o.getId())
                .flagKey(o.getFlagKey())
                .enabled(o.isEnabled())
                .description(o.getDescription())
                .updatedBy(o.getUpdatedBy())
                .organizationId(o.getOrganizationId())
                .updatedAt(o.getUpdatedAt())
                .build())
            .toList();
    }
}
