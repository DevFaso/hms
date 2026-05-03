package com.example.hms.service;

import java.util.Set;
import java.util.UUID;

/**
 * MVP-6b: gates feature-flag reads against the active subscription
 * plan's {@code featureKeys} list.
 *
 * <p>Contract: an organization without an active subscription is
 * <strong>ungated</strong> — every flag passes through unchanged. This
 * preserves backwards-compatibility for tenants that pre-date plan
 * assignment and for super-admin / system callers that have no
 * tenant context.
 *
 * <p>Once a tenant has an ACTIVE row in
 * {@code platform.organization_subscriptions}, only feature keys
 * listed (case-insensitive, comma-separated) in the assigned plan's
 * {@code featureKeys} field are considered allowed; everything else
 * is forced off in {@link FeatureFlagService#listFlags}.
 *
 * <p>Empty {@code featureKeys} is treated as "no plan-tier features
 * advertised" — the caller stays ungated to avoid accidentally
 * disabling every flag for an org that is on a starter tier with an
 * empty feature list (versus a power tier with explicit keys).
 */
public interface SubscriptionFeatureGateService {

    /**
     * @return whether {@code featureKey} is allowed for
     *     {@code organizationId} per the active plan. {@code true}
     *     when no plan is active or {@code featureKey} is in the plan's
     *     feature list. The lookup is case-insensitive and tolerant of
     *     surrounding whitespace.
     */
    boolean isFeatureAllowedForOrg(UUID organizationId, String featureKey);

    /**
     * @return the subset of {@code keys} the active plan permits,
     *     preserving input order. When {@code organizationId} is null
     *     or has no active subscription, returns the input unchanged.
     */
    Set<String> filterAllowedKeys(UUID organizationId, Set<String> keys);
}
