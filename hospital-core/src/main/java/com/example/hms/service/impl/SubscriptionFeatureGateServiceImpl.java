package com.example.hms.service.impl;

import com.example.hms.model.platform.OrganizationSubscription;
import com.example.hms.model.platform.SubscriptionPlan;
import com.example.hms.repository.OrganizationSubscriptionRepository;
import com.example.hms.service.SubscriptionFeatureGateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SubscriptionFeatureGateServiceImpl implements SubscriptionFeatureGateService {

    private static final ObjectMapper FEATURE_KEYS_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final OrganizationSubscriptionRepository subscriptionRepository;

    @Override
    public boolean isFeatureAllowedForOrg(UUID organizationId, String featureKey) {
        if (organizationId == null || featureKey == null || featureKey.isBlank()) {
            // No tenant context (system caller / super admin) → ungated.
            // Blank key passes through so callers don't have to short-circuit.
            return true;
        }
        Set<String> allowed = loadAllowedKeysForOrg(organizationId);
        if (allowed == null) {
            // No active subscription, or active plan has empty featureKeys —
            // both treated as "no plan-tier gating yet". See interface
            // docstring for rationale.
            return true;
        }
        return allowed.contains(normalize(featureKey));
    }

    @Override
    public Set<String> filterAllowedKeys(UUID organizationId, Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return keys == null ? Set.of() : keys;
        }
        if (organizationId == null) {
            return keys;
        }
        Set<String> allowed = loadAllowedKeysForOrg(organizationId);
        if (allowed == null) {
            return keys;
        }
        // Preserve input ordering — callers (FeatureFlagServiceImpl) merge
        // the result back into a LinkedHashMap and a stable order keeps
        // the response deterministic for cache-busting.
        return keys.stream()
            .filter(key -> key != null && allowed.contains(normalize(key)))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * @return null when the org has no active subscription or the active
     *     plan exposes no feature keys; an immutable lower-cased set
     *     otherwise.
     */
    private Set<String> loadAllowedKeysForOrg(UUID organizationId) {
        Optional<OrganizationSubscription> active = subscriptionRepository
            .findByOrganizationIdAndStatus(organizationId, OrganizationSubscription.Status.ACTIVE);
        if (active.isEmpty()) {
            return null;
        }
        SubscriptionPlan plan = active.get().getPlan();
        if (plan == null) {
            log.warn("[FEATURE-GATE] Active subscription for org {} has no plan attached", organizationId);
            return null;
        }
        // MVP-6c: prefer the jsonb mirror when populated. Fall back to the
        // legacy comma-separated TEXT column so older plan rows that were
        // written before the V85 backfill (or rows partial-rolled-back to
        // the prior schema) keep working.
        Set<String> fromJsonb = parseJsonbKeys(plan.getFeatureKeysJson());
        if (!fromJsonb.isEmpty()) {
            return fromJsonb;
        }
        String featureKeys = plan.getFeatureKeys();
        if (featureKeys == null || featureKeys.isBlank()) {
            return null;
        }
        return Arrays.stream(featureKeys.split(","))
            .map(this::normalize)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> parseJsonbKeys(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> raw = FEATURE_KEYS_MAPPER.readValue(json, STRING_LIST);
            if (raw == null || raw.isEmpty()) {
                return Set.of();
            }
            return raw.stream()
                .map(this::normalize)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException | java.io.IOException ex) {
            // Malformed jsonb cell — degrade to ungated rather than block
            // every flag for the org. Logged so the operator can retag the
            // plan; the legacy TEXT column is still consulted on next call.
            log.warn("[FEATURE-GATE] feature_keys_jsonb parse failed: {}", ex.getMessage());
            return Set.of();
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
