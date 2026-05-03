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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SubscriptionServiceImpl (MVP-6)")
class SubscriptionServiceImplTest {

    private SubscriptionPlanRepository planRepository;
    private OrganizationSubscriptionRepository subscriptionRepository;
    private OrganizationRepository organizationRepository;
    private SubscriptionMapper mapper;
    private SubscriptionServiceImpl service;

    @BeforeEach
    void setUp() {
        planRepository = mock(SubscriptionPlanRepository.class);
        subscriptionRepository = mock(OrganizationSubscriptionRepository.class);
        organizationRepository = mock(OrganizationRepository.class);
        mapper = new SubscriptionMapper();
        service = new SubscriptionServiceImpl(
            planRepository, subscriptionRepository, organizationRepository, mapper);

        when(planRepository.save(any(SubscriptionPlan.class)))
            .thenAnswer(inv -> {
                SubscriptionPlan p = inv.getArgument(0);
                if (p.getId() == null) {
                    p.setId(UUID.randomUUID());
                }
                return p;
            });
        when(subscriptionRepository.save(any(OrganizationSubscription.class)))
            .thenAnswer(inv -> {
                OrganizationSubscription s = inv.getArgument(0);
                if (s.getId() == null) {
                    s.setId(UUID.randomUUID());
                }
                return s;
            });
    }

    private SubscriptionPlan plan(String tier, boolean active) {
        SubscriptionPlan plan = SubscriptionPlan.builder()
            .name(tier + " Plan")
            .tierCode(tier)
            .description("desc")
            .monthlyPriceCents(1000L)
            .currency("USD")
            .includedSeats(5)
            .featureKeys("feature.a,feature.b")
            .active(active)
            .build();
        plan.setId(UUID.randomUUID());
        return plan;
    }

    private Organization org(String name) {
        Organization o = new Organization();
        o.setId(UUID.randomUUID());
        o.setName(name);
        return o;
    }

    @Test
    @DisplayName("listPlans(activeOnly=true) delegates to findByActiveTrue")
    void listActiveOnly() {
        SubscriptionPlan p = plan("PRO", true);
        when(planRepository.findByActiveTrue()).thenReturn(List.of(p));

        List<SubscriptionPlanResponseDTO> out = service.listPlans(true);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getTierCode()).isEqualTo("PRO");
        verify(planRepository, never()).findAll();
    }

    @Test
    @DisplayName("listPlans(activeOnly=false) returns full catalogue")
    void listAll() {
        when(planRepository.findAll()).thenReturn(List.of(plan("PRO", true), plan("BASIC", false)));

        assertThat(service.listPlans(false)).hasSize(2);
    }

    @Test
    @DisplayName("createPlan persists with defaults applied")
    void createPlanDefaults() {
        SubscriptionPlanRequestDTO req = SubscriptionPlanRequestDTO.builder()
            .name("Pro")
            .tierCode("PRO")
            .monthlyPriceCents(2000L)
            .includedSeats(10)
            .build();

        SubscriptionPlanResponseDTO out = service.createPlan(req);

        ArgumentCaptor<SubscriptionPlan> captor = ArgumentCaptor.forClass(SubscriptionPlan.class);
        verify(planRepository).save(captor.capture());
        SubscriptionPlan saved = captor.getValue();
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getFeatureKeys()).isEmpty();
        assertThat(saved.isActive()).isTrue();
        assertThat(out.getTierCode()).isEqualTo("PRO");
    }

    @Test
    @DisplayName("createPlan honours explicit currency / featureKeys / active flag")
    void createPlanExplicitFields() {
        SubscriptionPlanRequestDTO req = SubscriptionPlanRequestDTO.builder()
            .name("Basic")
            .tierCode("BASIC")
            .monthlyPriceCents(0L)
            .includedSeats(2)
            .currency("XOF")
            .featureKeys("a,b")
            .active(false)
            .build();

        service.createPlan(req);

        ArgumentCaptor<SubscriptionPlan> captor = ArgumentCaptor.forClass(SubscriptionPlan.class);
        verify(planRepository).save(captor.capture());
        SubscriptionPlan saved = captor.getValue();
        assertThat(saved.getCurrency()).isEqualTo("XOF");
        assertThat(saved.getFeatureKeys()).isEqualTo("a,b");
        assertThat(saved.isActive()).isFalse();
    }

    @Test
    @DisplayName("updatePlan throws ResourceNotFound for unknown id")
    void updatePlanMissing() {
        UUID id = UUID.randomUUID();
        when(planRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePlan(id, new SubscriptionPlanRequestDTO()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updatePlan applies partial fields and preserves the rest")
    void updatePlanApplies() {
        SubscriptionPlan existing = plan("PRO", true);
        when(planRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        SubscriptionPlanRequestDTO req = SubscriptionPlanRequestDTO.builder()
            .name("Pro v2")
            .tierCode("PRO")
            .monthlyPriceCents(1500L)
            .includedSeats(7)
            .build();

        SubscriptionPlanResponseDTO out = service.updatePlan(existing.getId(), req);

        assertThat(out.getName()).isEqualTo("Pro v2");
        assertThat(out.getCurrency()).isEqualTo("USD");
        assertThat(out.isActive()).isTrue();
    }

    @Test
    @DisplayName("updatePlan deactivates when active=false")
    void updatePlanDeactivate() {
        SubscriptionPlan existing = plan("PRO", true);
        when(planRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        SubscriptionPlanRequestDTO req = SubscriptionPlanRequestDTO.builder()
            .name("Pro")
            .tierCode("PRO")
            .monthlyPriceCents(1500L)
            .includedSeats(7)
            .currency("EUR")
            .featureKeys("only.this")
            .active(false)
            .build();

        SubscriptionPlanResponseDTO out = service.updatePlan(existing.getId(), req);

        assertThat(out.isActive()).isFalse();
        assertThat(out.getCurrency()).isEqualTo("EUR");
        assertThat(out.getFeatureKeys()).isEqualTo("only.this");
    }

    @Test
    @DisplayName("deactivatePlan flips active flag")
    void deactivatePlan() {
        SubscriptionPlan p = plan("PRO", true);
        when(planRepository.findById(p.getId())).thenReturn(Optional.of(p));

        service.deactivatePlan(p.getId());

        assertThat(p.isActive()).isFalse();
        verify(planRepository).save(p);
    }

    @Test
    @DisplayName("deactivatePlan throws ResourceNotFound for unknown id")
    void deactivatePlanMissing() {
        UUID id = UUID.randomUUID();
        when(planRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivatePlan(id))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("assignPlan rejects when plan is not active")
    void assignInactivePlanRejected() {
        Organization o = org("Korle Bu");
        SubscriptionPlan p = plan("PRO", false);
        when(organizationRepository.findById(o.getId())).thenReturn(Optional.of(o));
        when(planRepository.findById(p.getId())).thenReturn(Optional.of(p));

        OrganizationSubscriptionRequestDTO req = OrganizationSubscriptionRequestDTO.builder()
            .planId(p.getId())
            .seatLimit(5)
            .build();

        assertThatThrownBy(() -> service.assignPlan(o.getId(), req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("deactivated subscription plan");
    }

    @Test
    @DisplayName("assignPlan creates an ACTIVE subscription when no prior exists")
    void assignNoPrior() {
        Organization o = org("Korle Bu");
        SubscriptionPlan p = plan("PRO", true);
        when(organizationRepository.findById(o.getId())).thenReturn(Optional.of(o));
        when(planRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            o.getId(), OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.empty());

        OrganizationSubscriptionRequestDTO req = OrganizationSubscriptionRequestDTO.builder()
            .planId(p.getId())
            .seatLimit(20)
            .billingPeriod(OrganizationSubscription.BillingPeriod.ANNUAL)
            .build();

        OrganizationSubscriptionResponseDTO out = service.assignPlan(o.getId(), req);

        assertThat(out.getStatus()).isEqualTo("ACTIVE");
        assertThat(out.getBillingPeriod()).isEqualTo("ANNUAL");
        assertThat(out.getOrganizationId()).isEqualTo(o.getId());
        // Only one save (the new subscription); no prior to cancel.
        verify(subscriptionRepository, times(1)).save(any(OrganizationSubscription.class));
    }

    @Test
    @DisplayName("assignPlan cancels the existing ACTIVE row before saving the new one")
    void assignSupersedesExisting() {
        Organization o = org("Korle Bu");
        SubscriptionPlan oldPlan = plan("BASIC", true);
        SubscriptionPlan newPlan = plan("PRO", true);
        OrganizationSubscription existing = OrganizationSubscription.builder()
            .organization(o).plan(oldPlan).seatLimit(2)
            .status(OrganizationSubscription.Status.ACTIVE)
            .billingPeriod(OrganizationSubscription.BillingPeriod.MONTHLY)
            .build();
        existing.setId(UUID.randomUUID());

        when(organizationRepository.findById(o.getId())).thenReturn(Optional.of(o));
        when(planRepository.findById(newPlan.getId())).thenReturn(Optional.of(newPlan));
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            o.getId(), OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.of(existing));

        OrganizationSubscriptionRequestDTO req = OrganizationSubscriptionRequestDTO.builder()
            .planId(newPlan.getId())
            .seatLimit(10)
            .build();

        service.assignPlan(o.getId(), req);

        assertThat(existing.getStatus()).isEqualTo(OrganizationSubscription.Status.CANCELLED);
        assertThat(existing.getEndsAt()).isNotNull();
        // Two saves: cancel existing + persist new.
        verify(subscriptionRepository, times(2)).save(any(OrganizationSubscription.class));
    }

    @Test
    @DisplayName("assignPlan defaults billingPeriod to MONTHLY when null")
    void assignDefaultsBillingPeriod() {
        Organization o = org("Korle Bu");
        SubscriptionPlan p = plan("PRO", true);
        when(organizationRepository.findById(o.getId())).thenReturn(Optional.of(o));
        when(planRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(subscriptionRepository.findByOrganizationIdAndStatus(any(), any()))
            .thenReturn(Optional.empty());

        OrganizationSubscriptionRequestDTO req = OrganizationSubscriptionRequestDTO.builder()
            .planId(p.getId())
            .seatLimit(5)
            .build();

        OrganizationSubscriptionResponseDTO out = service.assignPlan(o.getId(), req);

        assertThat(out.getBillingPeriod()).isEqualTo("MONTHLY");
    }

    @Test
    @DisplayName("assignPlan throws ResourceNotFound when organization is missing")
    void assignMissingOrg() {
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

        OrganizationSubscriptionRequestDTO req = OrganizationSubscriptionRequestDTO.builder()
            .planId(UUID.randomUUID()).seatLimit(1).build();

        assertThatThrownBy(() -> service.assignPlan(orgId, req))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Organization not found");
    }

    @Test
    @DisplayName("assignPlan throws ResourceNotFound when plan is missing")
    void assignMissingPlan() {
        Organization o = org("Korle Bu");
        when(organizationRepository.findById(o.getId())).thenReturn(Optional.of(o));
        when(planRepository.findById(any())).thenReturn(Optional.empty());

        OrganizationSubscriptionRequestDTO req = OrganizationSubscriptionRequestDTO.builder()
            .planId(UUID.randomUUID()).seatLimit(1).build();

        assertThatThrownBy(() -> service.assignPlan(o.getId(), req))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("SubscriptionPlan not found");
    }

    @Test
    @DisplayName("cancel marks the subscription as CANCELLED with endsAt populated")
    void cancelHappyPath() {
        Organization o = org("Korle Bu");
        SubscriptionPlan p = plan("PRO", true);
        OrganizationSubscription sub = OrganizationSubscription.builder()
            .organization(o).plan(p).seatLimit(5)
            .status(OrganizationSubscription.Status.ACTIVE)
            .billingPeriod(OrganizationSubscription.BillingPeriod.MONTHLY)
            .build();
        sub.setId(UUID.randomUUID());
        when(subscriptionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));

        OrganizationSubscriptionResponseDTO out = service.cancel(o.getId(), sub.getId());

        assertThat(out.getStatus()).isEqualTo("CANCELLED");
        assertThat(sub.getEndsAt()).isNotNull();
    }

    @Test
    @DisplayName("cancel rejects when subscription belongs to a different organization")
    void cancelCrossTenantRejected() {
        Organization owner = org("Korle Bu");
        Organization stranger = org("Faso Mutuelle");
        OrganizationSubscription sub = OrganizationSubscription.builder()
            .organization(owner).plan(plan("PRO", true)).seatLimit(5)
            .status(OrganizationSubscription.Status.ACTIVE)
            .billingPeriod(OrganizationSubscription.BillingPeriod.MONTHLY)
            .build();
        sub.setId(UUID.randomUUID());
        when(subscriptionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.cancel(stranger.getId(), sub.getId()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong");
    }

    @Test
    @DisplayName("cancel throws ResourceNotFound for unknown subscription id")
    void cancelMissing() {
        UUID subId = UUID.randomUUID();
        when(subscriptionRepository.findById(subId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), subId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("listForOrganization returns mapped DTOs")
    void listForOrganization() {
        Organization o = org("Korle Bu");
        OrganizationSubscription sub = OrganizationSubscription.builder()
            .organization(o).plan(plan("PRO", true)).seatLimit(5)
            .status(OrganizationSubscription.Status.ACTIVE)
            .billingPeriod(OrganizationSubscription.BillingPeriod.MONTHLY)
            .build();
        sub.setId(UUID.randomUUID());
        when(subscriptionRepository.findByOrganizationId(o.getId())).thenReturn(List.of(sub));

        List<OrganizationSubscriptionResponseDTO> out = service.listForOrganization(o.getId());

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("getActiveForOrganization returns null when no active row exists")
    void getActiveAbsent() {
        UUID orgId = UUID.randomUUID();
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            orgId, OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.empty());

        assertThat(service.getActiveForOrganization(orgId)).isNull();
    }

    @Test
    @DisplayName("getActiveForOrganization returns the mapped active subscription when present")
    void getActivePresent() {
        Organization o = org("Korle Bu");
        OrganizationSubscription sub = OrganizationSubscription.builder()
            .organization(o).plan(plan("PRO", true)).seatLimit(5)
            .status(OrganizationSubscription.Status.ACTIVE)
            .billingPeriod(OrganizationSubscription.BillingPeriod.MONTHLY)
            .build();
        sub.setId(UUID.randomUUID());
        when(subscriptionRepository.findByOrganizationIdAndStatus(
            o.getId(), OrganizationSubscription.Status.ACTIVE))
            .thenReturn(Optional.of(sub));

        OrganizationSubscriptionResponseDTO out = service.getActiveForOrganization(o.getId());

        assertThat(out).isNotNull();
        assertThat(out.getOrganizationId()).isEqualTo(o.getId());
    }
}
