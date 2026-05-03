package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.OrganizationSubscriptionRequestDTO;
import com.example.hms.payload.dto.superadmin.OrganizationSubscriptionResponseDTO;
import com.example.hms.payload.dto.superadmin.SubscriptionPlanRequestDTO;
import com.example.hms.payload.dto.superadmin.SubscriptionPlanResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * MVP-6: Plan catalogue + per-organization subscription management.
 * Plan-tier-driven feature enforcement against
 * {@link com.example.hms.config.FeatureFlagProperties} is deferred to
 * MVP-6b — this MVP ships the schema, CRUD, and assignment surface.
 */
public interface SubscriptionService {

    List<SubscriptionPlanResponseDTO> listPlans(boolean activeOnly);

    SubscriptionPlanResponseDTO createPlan(SubscriptionPlanRequestDTO request);

    SubscriptionPlanResponseDTO updatePlan(UUID planId, SubscriptionPlanRequestDTO request);

    void deactivatePlan(UUID planId);

    OrganizationSubscriptionResponseDTO assignPlan(
        UUID organizationId, OrganizationSubscriptionRequestDTO request);

    OrganizationSubscriptionResponseDTO cancel(UUID organizationId, UUID subscriptionId);

    List<OrganizationSubscriptionResponseDTO> listForOrganization(UUID organizationId);

    OrganizationSubscriptionResponseDTO getActiveForOrganization(UUID organizationId);
}
