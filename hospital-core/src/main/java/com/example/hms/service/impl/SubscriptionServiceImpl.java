package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
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
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.hms.service.FeatureFlagService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {
    private static final String SUBSCRIPTION_PLAN_NOT_FOUND_KEY = "subscription.plan.notFound";


    private static final String DEFAULT_CURRENCY = "USD";

    /** Prices are stored in hundredths of the currency unit, whatever its minor unit. */
    private static final long HUNDREDTHS_PER_UNIT = 100L;

    private final SubscriptionPlanRepository planRepository;
    private final OrganizationSubscriptionRepository subscriptionRepository;
    private final OrganizationRepository organizationRepository;
    private final SubscriptionMapper mapper;
    private final FeatureFlagService featureFlagService;

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionPlanResponseDTO> listPlans(boolean activeOnly) {
        List<SubscriptionPlan> plans = activeOnly
            ? planRepository.findByActiveTrue()
            : planRepository.findAll();
        return plans.stream().map(this::toDtoWithUsage).toList();
    }

    /** QA D4071: the plan with the number of organizations it is the active subscription of. */
    private SubscriptionPlanResponseDTO toDtoWithUsage(SubscriptionPlan plan) {
        SubscriptionPlanResponseDTO dto = mapper.toDto(plan);
        if (dto != null && plan.getId() != null) {
            dto.setActiveOrganizationCount(subscriptionRepository.countByPlanIdAndStatus(
                plan.getId(), OrganizationSubscription.Status.ACTIVE));
        }
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listFeatureKeyCatalogue() {
        return featureFlagService.catalogueKeys();
    }

    /**
     * QA D4070: the keys a plan lists gate its organizations' features, so a
     * key the platform does not have is refused on its field rather than
     * stored: an invented or mistyped key switched modules off without a
     * word. Each key is kept in the catalogue's own spelling, once.
     */
    private List<String> requireKnownFeatureKeys(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> catalogue = featureFlagService.catalogueKeys();
        List<String> kept = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String part : raw.split(",")) {
            String key = part.trim();
            if (key.isEmpty()) {
                continue;
            }
            String known = catalogue.stream().filter(key::equalsIgnoreCase).findFirst().orElse(null);
            if (known == null) {
                unknown.add(key);
            } else if (!kept.contains(known)) {
                kept.add(known);
            }
        }
        if (!unknown.isEmpty()) {
            throw new FieldBusinessException("featureKeys", "subscription.plan.featureKeys.unknown",
                String.join(", ", unknown));
        }
        return kept;
    }

    /**
     * Both columns the gate reads: the comma-separated text and its JSON
     * mirror (lower-cased). Catalogue keys hold no quote or backslash, so the
     * array needs no escaping.
     */
    private static void applyFeatureKeys(SubscriptionPlan plan, List<String> keys) {
        plan.setFeatureKeys(String.join(",", keys));
        plan.setFeatureKeysJson(keys.stream()
            .filter(key -> key.indexOf('"') < 0 && key.indexOf('\\') < 0)
            .map(key -> '"' + key.toLowerCase(Locale.ROOT) + '"')
            .collect(Collectors.joining(",", "[", "]")));
    }

    @Override
    @Transactional
    public SubscriptionPlanResponseDTO createPlan(SubscriptionPlanRequestDTO request) {
        String currency = normalizeCurrency(request.getCurrency());
        requirePriceFitsCurrency(request.getMonthlyPriceCents(), currency);
        requireTierCodeFree(request.getTierCode());
        List<String> featureKeys = requireKnownFeatureKeys(request.getFeatureKeys());
        SubscriptionPlan plan = SubscriptionPlan.builder()
            .name(request.getName())
            .tierCode(request.getTierCode())
            .description(request.getDescription())
            .monthlyPriceCents(request.getMonthlyPriceCents())
            .currency(currency)
            .includedSeats(request.getIncludedSeats())
            .active(request.getActive() == null || request.getActive())
            .build();
        applyFeatureKeys(plan, featureKeys);
        return toDtoWithUsage(planRepository.save(plan));
    }

    @Override
    @Transactional
    public SubscriptionPlanResponseDTO updatePlan(UUID planId, SubscriptionPlanRequestDTO request) {
        SubscriptionPlan plan = planRepository.findByIdForUpdate(planId)
            .orElseThrow(() -> new ResourceNotFoundException(SUBSCRIPTION_PLAN_NOT_FOUND_KEY, planId));
        String currency = request.getCurrency() != null
            ? normalizeCurrency(request.getCurrency())
            : plan.getCurrency();
        requirePriceFitsCurrency(request.getMonthlyPriceCents(), currency);
        // QA D850: deactivating through an edit is the same act as DELETE.
        if (Boolean.FALSE.equals(request.getActive()) && plan.isActive()) {
            requireNotInUse(plan);
        }
        if (request.getTierCode() != null && !request.getTierCode().equalsIgnoreCase(plan.getTierCode())) {
            requireTierCodeFree(request.getTierCode());
        }
        plan.setName(request.getName());
        plan.setTierCode(request.getTierCode());
        plan.setDescription(request.getDescription());
        plan.setMonthlyPriceCents(request.getMonthlyPriceCents());
        plan.setCurrency(currency);
        plan.setIncludedSeats(request.getIncludedSeats());
        if (request.getFeatureKeys() != null) {
            applyFeatureKeys(plan, requireKnownFeatureKeys(request.getFeatureKeys()));
        }
        if (request.getActive() != null) {
            plan.setActive(request.getActive());
        }
        return toDtoWithUsage(planRepository.save(plan));
    }

    @Override
    @Transactional
    public void deactivatePlan(UUID planId) {
        SubscriptionPlan plan = planRepository.findByIdForUpdate(planId)
            .orElseThrow(() -> new ResourceNotFoundException(SUBSCRIPTION_PLAN_NOT_FOUND_KEY, planId));
        if (!plan.isActive()) {
            return;
        }
        requireNotInUse(plan);
        plan.setActive(false);
        planRepository.save(plan);
    }

    @Override
    @Transactional
    public SubscriptionPlanResponseDTO reactivatePlan(UUID planId) {
        SubscriptionPlan plan = planRepository.findByIdForUpdate(planId)
            .orElseThrow(() -> new ResourceNotFoundException(SUBSCRIPTION_PLAN_NOT_FOUND_KEY, planId));
        if (!plan.isActive()) {
            plan.setActive(true);
            plan = planRepository.save(plan);
        }
        return toDtoWithUsage(plan);
    }

    /**
     * QA CX-005: a tier code another plan holds is refused on its field, so the
     * form marks it. It used to reach the unique constraint and come back as
     * "this record already exists", naming nothing.
     */
    private void requireTierCodeFree(String tierCode) {
        if (tierCode != null && planRepository.existsByTierCodeIgnoreCase(tierCode)) {
            throw new FieldBusinessException("tierCode", "subscription.plan.tierCode.duplicate", tierCode);
        }
    }

    /**
     * QA D850: a plan an organization is subscribed to cannot be deactivated —
     * the subscription would stay ACTIVE on a plan the catalogue no longer
     * offers. The operator moves or cancels those subscriptions first.
     */
    private void requireNotInUse(SubscriptionPlan plan) {
        long inUse = subscriptionRepository.countByPlanIdAndStatus(
            plan.getId(), OrganizationSubscription.Status.ACTIVE);
        if (inUse > 0) {
            // "planId:" names the field for the conflict body; the localized
            // text after it may itself contain a colon.
            throw new ConflictException("planId:"
                + MessageUtil.resolveOrRaw("subscription.plan.inUse", inUse));
        }
    }

    /**
     * QA D865: an ISO 4217 code ("XOF", "EUR"), upper-cased; anything else is
     * refused rather than stored and later shown as an unknown currency.
     */
    private static String normalizeCurrency(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CURRENCY;
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (isoCurrency(code) == null) {
            throw new FieldBusinessException("currency", "subscription.plan.currency.invalid", raw.trim());
        }
        return code;
    }

    /** The ISO 4217 currency for {@code code}, or null when there is none. */
    private static Currency isoCurrency(String code) {
        if (code == null || code.length() != 3) {
            return null;
        }
        return Currency.getAvailableCurrencies().stream()
            .filter(c -> c.getCurrencyCode().equals(code))
            .findFirst()
            .orElse(null);
    }

    /**
     * QA D865: a currency with no minor unit (the CFA franc) has no centimes,
     * so its price is a whole number of units.
     */
    private static void requirePriceFitsCurrency(long priceHundredths, String currency) {
        Currency iso = isoCurrency(currency);
        if (iso != null && iso.getDefaultFractionDigits() == 0
                && priceHundredths % HUNDREDTHS_PER_UNIT != 0) {
            throw new FieldBusinessException("monthlyPriceCents",
                "subscription.plan.price.wholeUnits", currency);
        }
    }

    @Override
    @Transactional
    public OrganizationSubscriptionResponseDTO assignPlan(
        UUID organizationId, OrganizationSubscriptionRequestDTO request
    ) {
        Organization organization = organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException("organization.notFound", organizationId));
        SubscriptionPlan plan = planRepository.findByIdForUpdate(request.getPlanId())
            .orElseThrow(() -> new ResourceNotFoundException(SUBSCRIPTION_PLAN_NOT_FOUND_KEY, request.getPlanId()));

        // PR #228 review — refuse to assign a deactivated plan. The
        // controller summary advertises that deactivated plans "reject new
        // assignments", which only holds if we enforce it here.
        if (!plan.isActive()) {
            throw new com.example.hms.exception.FieldBusinessException("planId", "subscription.plan.inactive", plan.getTierCode());
        }

        OrganizationSubscription.BillingPeriod requestedPeriod = request.getBillingPeriod() == null
            ? OrganizationSubscription.BillingPeriod.MONTHLY
            : request.getBillingPeriod();
        // QA D4311: the same plan, seats and period again is not a change; it
        // used to end the subscription and open an identical one each time.
        boolean unchanged = subscriptionRepository
            .findByOrganizationIdAndStatus(organizationId, OrganizationSubscription.Status.ACTIVE)
            .filter(existing -> existing.getPlan() != null
                && plan.getId() != null && plan.getId().equals(existing.getPlan().getId())
                && existing.getSeatLimit() == request.getSeatLimit()
                && existing.getBillingPeriod() == requestedPeriod)
            .isPresent();
        if (unchanged) {
            throw new FieldBusinessException("planId", "subscription.plan.alreadyAssigned", plan.getName());
        }

        // Cancel any existing ACTIVE subscription so the partial unique index
        // (one ACTIVE per org) holds, in a single tx. Flushed at once (QA CX-011):
        // Hibernate runs INSERTs before UPDATEs, so with a plain save the new row
        // reached uq_orgsub_active_per_org while this one still held it.
        subscriptionRepository.findByOrganizationIdAndStatus(organizationId, OrganizationSubscription.Status.ACTIVE)
            .ifPresent(existing -> {
                existing.setStatus(OrganizationSubscription.Status.CANCELLED);
                existing.setEndsAt(Instant.now());
                subscriptionRepository.saveAndFlush(existing);
            });

        // PR #228 review — billing period is now a typed enum on the DTO,
        // so Spring rejects unknown values with HTTP 400 before we ever get
        // here. Null still defaults to MONTHLY for backwards compatibility.
        OrganizationSubscription.BillingPeriod period = request.getBillingPeriod() == null
            ? OrganizationSubscription.BillingPeriod.MONTHLY
            : request.getBillingPeriod();

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
    public OrganizationSubscriptionResponseDTO cancel(UUID organizationId, UUID subscriptionId) {
        OrganizationSubscription sub = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new ResourceNotFoundException("subscription.notFound", subscriptionId));
        // PR #228 review — verify the subscription belongs to the
        // organization on the URL. Otherwise a super admin who knows a
        // subscription id could cancel it via any org's cancel route.
        if (sub.getOrganization() == null
                || !organizationId.equals(sub.getOrganization().getId())) {
            throw new BusinessException("subscription.notInOrganization");
        }
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
