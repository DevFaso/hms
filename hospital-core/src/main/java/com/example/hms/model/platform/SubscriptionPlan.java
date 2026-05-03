package com.example.hms.model.platform;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MVP-6: Tier-driven subscription plan. {@code featureKeys} is stored as
 * a comma-separated string for the MVP — the future MVP-6b moves it to
 * a jsonb column once enforcement against {@link FeatureFlagOverride}
 * lands.
 */
@Entity
@Table(name = "subscription_plans", schema = "platform")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class SubscriptionPlan extends BaseEntity {

    @NotBlank
    @Size(max = 120)
    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @NotBlank
    @Size(max = 60)
    @Column(name = "tier_code", nullable = false, unique = true, length = 60)
    private String tierCode;

    @Size(max = 1000)
    @Column(name = "description", length = 1000)
    private String description;

    @PositiveOrZero
    @Column(name = "monthly_price_cents", nullable = false)
    private long monthlyPriceCents;

    @NotBlank
    @Size(max = 10)
    @Column(name = "currency", nullable = false, length = 10)
    @Builder.Default
    private String currency = "USD";

    @PositiveOrZero
    @Column(name = "included_seats", nullable = false)
    private int includedSeats;

    @Column(name = "feature_keys", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String featureKeys = "";

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
