package com.example.hms.model.platform;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Organization;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * MVP-6: Per-organization subscription assignment. Partial unique index
 * on {@code (organization_id) WHERE status = 'ACTIVE'} (see V81) keeps
 * one ACTIVE subscription per org while allowing historical CANCELLED
 * rows to accumulate for billing audits.
 */
@Entity
@Table(name = "organization_subscriptions", schema = "platform")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class OrganizationSubscription extends BaseEntity {

    public enum Status {
        ACTIVE,
        CANCELLED,
        EXPIRED
    }

    public enum BillingPeriod {
        MONTHLY,
        QUARTERLY,
        ANNUAL
    }

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @PositiveOrZero
    @Column(name = "seat_limit", nullable = false)
    private int seatLimit;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, length = 20)
    @Builder.Default
    private BillingPeriod billingPeriod = BillingPeriod.MONTHLY;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @NotNull
    @Column(name = "started_at", nullable = false)
    @Builder.Default
    private Instant startedAt = Instant.now();

    @Column(name = "ends_at")
    private Instant endsAt;
}
