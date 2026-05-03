package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.SubscriptionMapper;
import com.example.hms.model.Organization;
import com.example.hms.model.platform.OrganizationSubscription;
import com.example.hms.model.platform.SubscriptionPlan;
import com.example.hms.payload.dto.superadmin.OrganizationSubscriptionRequestDTO;
import com.example.hms.payload.dto.superadmin.OrganizationSubscriptionResponseDTO;
import com.example.hms.payload.dto.superadmin.SubscriptionPlanRequestDTO;
import com.example.hms.payload.dto.superadmin.SubscriptionPlanResponseDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSubscriptionRepository;
import com.example.hms.repository.SubscriptionPlanRepository;
import com.example.hms.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private static final String DEFAULT_CURRENCY = "USD";
    private static final String DEFAULT_BILLING_PERIOD = "MONTHLY";

    private final SubscriptionPlanRepository planRepository;
    private final OrganizationSubscriptionRepository subscriptionRepository;
    private final OrganizationRepository organizationRepository;
    private final SubscriptionMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionPlanResponseDTO> listPlans(boolean activeOnly) {
        List<SubscriptionPlan> plans = activeOnly
            ? planRepository.findByActiveTrue()
            : planRepository.findAll();
        return plans.stream().map(mapper::toDto).toList();
    }

    @Override
    @Transactional
    public SubscriptionPlanResponseDTO createPlan(SubscriptionPlanRequestDTO request) {
        SubscriptionPlan plan = SubscriptionPlan.builder()
            .name(request.getName())
            .tierCode(request.getTierCode())
            .description(request.getDescription())
            .monthlyPriceCents(request.getMonthlyPriceCents())
            .currency(request.getCurrency() == null ? DEFAULT_CURRENCY : request.getCurrency())
            .includedSeats(request.getIncludedSeats())
            .featureKeys(request.getFeatureKeys() == null ? "" : request.getFeatureKeys())
            .active(request.getActive() == null || request.getActive())
            .build();
        return mapper.toDto(planRepository.save(plan));
    }

    @Override
    @Transactional
    public SubscriptionPlanResponseDTO updatePlan(UUID planId, SubscriptionPlanRequestDTO request) {
        SubscriptionPlan plan = planRepository.findById(planId)
            .orElseThrow(() -> new ResourceNotFoundException("SubscriptionPlan not found: " + planId));
        plan.setName(request.getName());
        plan.setTierCode(request.getTierCode());
        plan.setDescription(request.getDescription());
        plan.setMonthlyPriceCents(request.getMonthlyPriceCents());
        if (request.getCurrency() != null) {
            plan.setCurrency(request.getCurrency());
        }
        plan.setIncludedSeats(request.getIncludedSeats());
        if (request.getFeatureKeys() != null) {
            plan.setFeatureKeys(request.getFeatureKeys());
        }
        if (request.getActive() != null) {
            plan.setActive(request.getActive());
        }
        return mapper.toDto(planRepository.save(plan));
    }

    @Override
    @Transactional
    public void deactivatePlan(UUID planId) {
        SubscriptionPlan plan = planRepository.findById(planId)
            .orElseThrow(() -> new ResourceNotFoundException("SubscriptionPlan not found: " + planId));
        plan.setActive(false);
        planRepository.save(plan);
    }

    @Override
    @Transactional
    public OrganizationSubscriptionResponseDTO assignPlan(
        UUID organizationId, OrganizationSubscriptionRequestDTO request
    ) {
        Organization organization = organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + organizationId));
        SubscriptionPlan plan = planRepository.findById(request.getPlanId())
            .orElseThrow(() -> new ResourceNotFoundException("SubscriptionPlan not found: " + request.getPlanId()));

        // Cancel any existing ACTIVE subscription so the partial unique index
        // (one ACTIVE per org) holds. Done in-memory then saved — keeps the
        // entire assignment in a single tx.
        subscriptionRepository.findByOrganizationIdAndStatus(organizationId, OrganizationSubscription.Status.ACTIVE)
            .ifPresent(existing -> {
                existing.setStatus(OrganizationSubscription.Status.CANCELLED);
                existing.setEndsAt(Instant.now());
                subscriptionRepository.save(existing);
            });

        OrganizationSubscription.BillingPeriod period;
        try {
            period = request.getBillingPeriod() == null
                ? OrganizationSubscription.BillingPeriod.MONTHLY
                : OrganizationSubscription.BillingPeriod.valueOf(request.getBillingPeriod());
        } catch (IllegalArgumentException ex) {
            period = OrganizationSubscription.BillingPeriod.valueOf(DEFAULT_BILLING_PERIOD);
        }

        OrganizationSubscription sub = OrganizationSubscription.builder()
            .organization(organization)
            .plan(plan)
            .seatLimit(request.getSeatLimit())
            .billingPeriod(period)
            .status(OrganizationSubscription.Status.ACTIVE)
            .startedAt(Instant.now())
            .build();
        return mapper.toDto(subscriptionRepository.save(sub));
    }

    @Override
    @Transactional
    public OrganizationSubscriptionResponseDTO cancel(UUID subscriptionId) {
        OrganizationSubscription sub = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new ResourceNotFoundException("Subscription not found: " + subscriptionId));
        sub.setStatus(OrganizationSubscription.Status.CANCELLED);
        sub.setEndsAt(Instant.now());
        return mapper.toDto(subscriptionRepository.save(sub));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationSubscriptionResponseDTO> listForOrganization(UUID organizationId) {
        return subscriptionRepository.findByOrganizationId(organizationId).stream()
            .map(mapper::toDto)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationSubscriptionResponseDTO getActiveForOrganization(UUID organizationId) {
        return subscriptionRepository
            .findByOrganizationIdAndStatus(organizationId, OrganizationSubscription.Status.ACTIVE)
            .map(mapper::toDto)
            .orElse(null);
    }
}
