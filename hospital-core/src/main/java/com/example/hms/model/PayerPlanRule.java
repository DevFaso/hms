package com.example.hms.model;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Billing-money plan PR 6 (V305): one exception to a {@link PayerPlan}'s default, on exactly one target - a price-list
 * {@link #category} or one {@link #priceListItemId price-list item} of the same hospital. It overrides the rate
 * ({@link #coveragePercent}), {@link #excluded excludes} the target, and/or caps the payer's share per act
 * ({@link #perActCeiling}). The most specific rule wins in PR 7: item, then category, then the plan default.
 *
 * <p>Versioned like a price (V302): insert-only and effective-dated. A new rule for the same target closes the one in
 * force on the day before it starts. The only update is {@code PayerCatalogueService} setting {@link #effectiveTo}
 * (with who ended it, when and why); {@code effectiveTo = effectiveFrom - 1} marks a rule replaced or withdrawn before
 * it applied, kept in the history.
 */
@Entity
@Table(
    name = "payer_plan_rules",
    schema = "billing",
    indexes = {
        @Index(name = "idx_payer_rule_plan", columnList = "plan_id, effective_from"),
        @Index(name = "idx_payer_rule_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
// Identity is the id, as for every BaseEntity row; adding columns adds no equality.
@SuppressWarnings("java:S2160")
public class PayerPlanRule extends BaseEntity implements TenantScoped {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    /** The category the rule applies to; null for an item rule. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30, updatable = false)
    private PriceListCategory category;

    /** The price-list item the rule applies to; null for a category rule. */
    @Column(name = "price_list_item_id", updatable = false)
    private UUID priceListItemId;

    /** 0 to 100; null keeps the plan's default rate. */
    @Column(name = "coverage_percent", updatable = false)
    private Integer coveragePercent;

    @Column(name = "excluded", nullable = false, updatable = false)
    private boolean excluded;

    /** Whole francs, more than 0: the most the payer pays for one act; null when none. */
    @Column(name = "per_act_ceiling", precision = 12, scale = 2, updatable = false)
    private BigDecimal perActCeiling;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** When, by whom and why the rule was ended or withdrawn on purpose; null when a newer rule took over. */
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "ended_by")
    private UUID endedBy;

    @Column(name = "end_reason", length = 500)
    private String endReason;

    /** Replaced or withdrawn before it started: its range is empty, so it never applies. */
    public boolean isCancelled() {
        return effectiveTo != null && effectiveTo.isBefore(effectiveFrom);
    }

    /** Whether this rule applies on {@code day}. */
    public boolean coversDay(LocalDate day) {
        return !isCancelled() && !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /** Whether this rule applies to the target named by {@code otherCategory} or {@code otherItemId}. */
    public boolean hasTarget(PriceListCategory otherCategory, UUID otherItemId) {
        return category != null
            ? category == otherCategory
            : priceListItemId != null && priceListItemId.equals(otherItemId);
    }

    @Override
    public UUID getTenantOrganizationId() {
        return null;
    }

    @Override
    public UUID getTenantHospitalId() {
        return hospitalId;
    }

    @Override
    public UUID getTenantDepartmentId() {
        return null;
    }

    /** The service always sets the hospital; this only fills a missing one from the pinned scope. */
    @Override
    public void applyTenantScope(HospitalContext context) {
        if (hospitalId == null && context != null) {
            hospitalId = ActingScopeResolver.pinnedHospitalIdOf(context);
        }
    }
}
