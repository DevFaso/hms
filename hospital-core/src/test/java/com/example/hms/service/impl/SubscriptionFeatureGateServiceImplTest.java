package com.example.hms.service.impl;

import com.example.hms.model.platform.OrganizationSubscription;
import com.example.hms.model.platform.SubscriptionPlan;
import com.example.hms.repository.OrganizationSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SubscriptionFeatureGateServiceImpl (MVP-6b)")
class SubscriptionFeatureGateServiceImplTest {

    private OrganizationSubscriptionRepository subscriptionRepository;
    private SubscriptionFeatureGateServiceImpl gate;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(OrganizationSubscriptionRepository.class);
        gate = new SubscriptionFeatureGateServiceImpl(subscriptionRepository);
    }

    private OrganizationSubscription subscriptionWithPlan(String featureKeys) {
        SubscriptionPlan plan = SubscriptionPlan.builder()
            .name("Pro")
            .tierCode("PRO")
            .monthlyPriceCents(1000)
            .currency("USD")
            .includedSeats(10)
            .featureKeys(featureKeys)
            .active(true)
            .build();
        plan.setId(UUID.randomUUID());

        OrganizationSubscription sub = OrganizationSubscription.builder()
            .plan(plan)
            .status(OrganizationSubscription.Status.ACTIVE)
            .build();
        sub.setId(UUID.randomUUID());
        return sub;
    }

    @Test
    @DisplayName("isFeatureAllowedForOrg returns true when organization is null (system caller)")
    void allowsSystemCallers() {
        assertThat(gate.isFeatureAllowedForOrg(null, "billing.advanced")).isTrue();
    }

    @Test
    @DisplayName("isFeatureAllowedForOrg returns true when feature key is blank")
    void blankKeyShortCircuits() {
        assertThat(gate.isFeatureAllowedForOrg(UUID.randomUUID(), "")).isTrue();
        assertThat(gate.isFeatureAllowedForOrg(UUID.randomUUID(), null)).isTrue();
    }

    @Test
    @DisplayName("isFeatureAllowedForOrg leaves org ungated when no active subscription exists")
    void noSubscriptionMeansUngated() {
        UUID orgId = UUID.randomUUID();
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            orgId, OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.empty());

        assertThat(gate.isFeatureAllowedForOrg(orgId, "any.key")).isTrue();
    }

    @Test
    @DisplayName("isFeatureAllowedForOrg leaves org ungated when active plan has empty featureKeys")
    void emptyFeatureKeysMeansUngated() {
        UUID orgId = UUID.randomUUID();
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            orgId, OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.of(subscriptionWithPlan("")));

        assertThat(gate.isFeatureAllowedForOrg(orgId, "anything")).isTrue();
    }

    @Test
    @DisplayName("isFeatureAllowedForOrg gates against the plan's allowed list (case-insensitive)")
    void enforcesPlanFeatureList() {
        UUID orgId = UUID.randomUUID();
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            orgId, OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.of(subscriptionWithPlan("billing.advanced, reports.export")));

        assertThat(gate.isFeatureAllowedForOrg(orgId, "billing.advanced")).isTrue();
        assertThat(gate.isFeatureAllowedForOrg(orgId, "BILLING.ADVANCED")).isTrue();
        assertThat(gate.isFeatureAllowedForOrg(orgId, "  reports.export  ")).isTrue();
        assertThat(gate.isFeatureAllowedForOrg(orgId, "chat.enabled")).isFalse();
    }

    @Test
    @DisplayName("filterAllowedKeys returns input unchanged when org has no active subscription")
    void filterPassesThroughWithoutSubscription() {
        UUID orgId = UUID.randomUUID();
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            orgId, OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.empty());
        Set<String> input = new LinkedHashSet<>(Set.of("a", "b", "c"));

        Set<String> result = gate.filterAllowedKeys(orgId, input);

        assertThat(result).isEqualTo(input);
    }

    @Test
    @DisplayName("filterAllowedKeys narrows to the plan's allowed keys, preserving input order")
    void filterNarrowsByPlan() {
        UUID orgId = UUID.randomUUID();
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            orgId, OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.of(subscriptionWithPlan("billing.advanced,reports.export")));

        Set<String> input = new LinkedHashSet<>();
        input.add("chat.enabled");
        input.add("reports.export");
        input.add("billing.advanced");

        Set<String> result = gate.filterAllowedKeys(orgId, input);

        assertThat(result).containsExactly("reports.export", "billing.advanced");
    }

    @Test
    @DisplayName("filterAllowedKeys handles null and empty inputs defensively")
    void filterHandlesEmptyInput() {
        UUID orgId = UUID.randomUUID();
        assertThat(gate.filterAllowedKeys(orgId, null)).isEmpty();
        assertThat(gate.filterAllowedKeys(orgId, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("isFeatureAllowedForOrg leaves org ungated when active subscription has a null plan")
    void nullPlanFallsBackToUngated() {
        UUID orgId = UUID.randomUUID();
        OrganizationSubscription orphan = OrganizationSubscription.builder()
            .status(OrganizationSubscription.Status.ACTIVE)
            .build();
        orphan.setId(UUID.randomUUID());
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            orgId, OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.of(orphan));

        assertThat(gate.isFeatureAllowedForOrg(orgId, "any.key")).isTrue();
    }

    // ── MVP-6c: jsonb feature_keys ────────────────────────────────────

    private OrganizationSubscription subscriptionWithJsonbKeys(String legacyKeys, String jsonbKeys) {
        SubscriptionPlan plan = SubscriptionPlan.builder()
            .name("Pro")
            .tierCode("PRO")
            .monthlyPriceCents(1000)
            .currency("USD")
            .includedSeats(10)
            .featureKeys(legacyKeys)
            .featureKeysJson(jsonbKeys)
            .active(true)
            .build();
        plan.setId(UUID.randomUUID());
        OrganizationSubscription sub = OrganizationSubscription.builder()
            .plan(plan)
            .status(OrganizationSubscription.Status.ACTIVE)
            .build();
        sub.setId(UUID.randomUUID());
        return sub;
    }

    @Test
    @DisplayName("MVP-6c: jsonb feature_keys wins over legacy TEXT when both are populated")
    void jsonbPrecedenceOverLegacyText() {
        UUID orgId = UUID.randomUUID();
        // Legacy says billing only; jsonb says reports only — the jsonb form wins.
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            orgId, OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.of(subscriptionWithJsonbKeys(
                "billing.advanced",
                "[\"reports.export\"]")));

        assertThat(gate.isFeatureAllowedForOrg(orgId, "reports.export")).isTrue();
        assertThat(gate.isFeatureAllowedForOrg(orgId, "billing.advanced")).isFalse();
    }

    @Test
    @DisplayName("MVP-6c: empty jsonb falls back to legacy TEXT column")
    void emptyJsonbFallsBackToLegacy() {
        UUID orgId = UUID.randomUUID();
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            orgId, OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.of(subscriptionWithJsonbKeys(
                "billing.advanced",
                "[]")));

        assertThat(gate.isFeatureAllowedForOrg(orgId, "billing.advanced")).isTrue();
        assertThat(gate.isFeatureAllowedForOrg(orgId, "reports.export")).isFalse();
    }

    @Test
    @DisplayName("MVP-6c: malformed jsonb falls back to legacy TEXT and does not block resolution")
    void malformedJsonbDoesNotBlockResolution() {
        UUID orgId = UUID.randomUUID();
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            orgId, OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.of(subscriptionWithJsonbKeys(
                "billing.advanced",
                "{not-valid-json")));

        // Falls back to legacy text — billing.advanced allowed, others denied.
        assertThat(gate.isFeatureAllowedForOrg(orgId, "billing.advanced")).isTrue();
        assertThat(gate.isFeatureAllowedForOrg(orgId, "reports.export")).isFalse();
    }
}
