package com.example.hms.service.impl;

import com.example.hms.config.FeatureFlagProperties;
import com.example.hms.model.platform.FeatureFlagOverride;
import com.example.hms.repository.platform.FeatureFlagOverrideRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.FeatureFlagService;
import com.example.hms.service.SubscriptionFeatureGateService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
            });
        return resolveEffectiveFlags(environmentOverride, locale);
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
            return subscriptionFeatureGate.isFeatureAllowedForOrg(organizationId, key);
        });
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
}
