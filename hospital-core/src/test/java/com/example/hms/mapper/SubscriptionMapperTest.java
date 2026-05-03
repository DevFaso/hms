package com.example.hms.mapper;

import com.example.hms.model.Organization;
import com.example.hms.model.platform.OrganizationSubscription;
import com.example.hms.model.platform.SubscriptionPlan;
import com.example.hms.payload.dto.superadmin.OrganizationSubscriptionResponseDTO;
import com.example.hms.payload.dto.superadmin.SubscriptionPlanResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SubscriptionMapper (MVP-6)")
class SubscriptionMapperTest {

    private final SubscriptionMapper mapper = new SubscriptionMapper();

    @Test
    @DisplayName("toDto(SubscriptionPlan) returns null on null input")
    void planNullSafe() {
        assertThat(mapper.toDto((SubscriptionPlan) null)).isNull();
    }

    @Test
    @DisplayName("toDto(SubscriptionPlan) maps every field")
    void planMapsAllFields() {
        SubscriptionPlan p = SubscriptionPlan.builder()
            .name("Pro")
            .tierCode("PRO")
            .description("Pro tier")
            .monthlyPriceCents(1500L)
            .currency("USD")
            .includedSeats(10)
            .featureKeys("a,b")
            .active(true)
            .build();
        p.setId(UUID.randomUUID());

        SubscriptionPlanResponseDTO out = mapper.toDto(p);

        assertThat(out.getId()).isEqualTo(p.getId());
        assertThat(out.getName()).isEqualTo("Pro");
        assertThat(out.getTierCode()).isEqualTo("PRO");
        assertThat(out.getDescription()).isEqualTo("Pro tier");
        assertThat(out.getMonthlyPriceCents()).isEqualTo(1500L);
        assertThat(out.getCurrency()).isEqualTo("USD");
        assertThat(out.getIncludedSeats()).isEqualTo(10);
        assertThat(out.getFeatureKeys()).isEqualTo("a,b");
        assertThat(out.isActive()).isTrue();
    }

    @Test
    @DisplayName("toDto(OrganizationSubscription) returns null on null input")
    void subscriptionNullSafe() {
        assertThat(mapper.toDto((OrganizationSubscription) null)).isNull();
    }

    @Test
    @DisplayName("toDto(OrganizationSubscription) maps every field on a fully populated row")
    void subscriptionMapsAllFields() {
        Organization o = new Organization();
        o.setId(UUID.randomUUID());
        o.setName("Korle Bu");

        SubscriptionPlan p = SubscriptionPlan.builder().name("Pro").tierCode("PRO").build();
        p.setId(UUID.randomUUID());

        OrganizationSubscription sub = OrganizationSubscription.builder()
            .organization(o).plan(p).seatLimit(20)
            .status(OrganizationSubscription.Status.ACTIVE)
            .billingPeriod(OrganizationSubscription.BillingPeriod.ANNUAL)
            .startedAt(Instant.parse("2026-04-01T00:00:00Z"))
            .endsAt(Instant.parse("2027-04-01T00:00:00Z"))
            .build();
        sub.setId(UUID.randomUUID());

        OrganizationSubscriptionResponseDTO out = mapper.toDto(sub);

        assertThat(out.getId()).isEqualTo(sub.getId());
        assertThat(out.getOrganizationId()).isEqualTo(o.getId());
        assertThat(out.getOrganizationName()).isEqualTo("Korle Bu");
        assertThat(out.getPlanId()).isEqualTo(p.getId());
        assertThat(out.getPlanName()).isEqualTo("Pro");
        assertThat(out.getPlanTierCode()).isEqualTo("PRO");
        assertThat(out.getSeatLimit()).isEqualTo(20);
        assertThat(out.getBillingPeriod()).isEqualTo("ANNUAL");
        assertThat(out.getStatus()).isEqualTo("ACTIVE");
        assertThat(out.getStartedAt()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
        assertThat(out.getEndsAt()).isEqualTo(Instant.parse("2027-04-01T00:00:00Z"));
    }

    @Test
    @DisplayName("toDto(OrganizationSubscription) tolerates null organization / plan / status / billingPeriod")
    void subscriptionTolerantOfNullAssociations() {
        OrganizationSubscription sub = OrganizationSubscription.builder()
            .organization(null).plan(null).seatLimit(0)
            .status(null).billingPeriod(null).build();
        sub.setId(UUID.randomUUID());

        OrganizationSubscriptionResponseDTO out = mapper.toDto(sub);

        assertThat(out.getOrganizationId()).isNull();
        assertThat(out.getOrganizationName()).isNull();
        assertThat(out.getPlanId()).isNull();
        assertThat(out.getPlanName()).isNull();
        assertThat(out.getPlanTierCode()).isNull();
        assertThat(out.getStatus()).isNull();
        assertThat(out.getBillingPeriod()).isNull();
    }
}
