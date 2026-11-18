package com.example.hms.model;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.UUID;

/**
 * Billing-money plan PR 6 (V305, review of #1202): one dated version of a {@link PayerPlan}'s terms - the default
 * coverage rate (whole percent) and the optional ceilings per invoice and per beneficiary per calendar year (whole
 * francs) - in force from {@link #effectiveFrom} to {@link #effectiveTo} (inclusive; open-ended while null).
 *
 * <p>Versioned like a price (V302): insert-only. A change adds a version from today or later; the one in force is
 * closed on the day before it starts, and a planned one is replaced only on confirmation ({@code effectiveTo =
 * effectiveFrom - 1}: never applies, kept in the history). The only update is {@code PayerCatalogueService} setting
 * {@link #effectiveTo}. The version in force on the service date applies (D-4).
 */
@Entity
@Table(
    name = "payer_plan_terms",
    schema = "billing",
    indexes = {
        @Index(name = "idx_payer_terms_plan", columnList = "plan_id, effective_from"),
        @Index(name = "idx_payer_terms_hospital", columnList = "hospital_id")
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
public class PayerPlanTerms extends BaseEntity implements TenantScoped {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    /** 0 to 100. */
    @Column(name = "default_coverage_percent", nullable = false, updatable = false)
    private Integer defaultCoveragePercent;

    /** Whole francs, more than 0; null when there is no ceiling per invoice. */
    @Column(name = "per_invoice_ceiling", precision = 12, scale = 2, updatable = false)
    private BigDecimal perInvoiceCeiling;

    /** Whole francs, more than 0, per beneficiary and calendar year (D-13); null when none. */
    @Column(name = "annual_ceiling_per_beneficiary", precision = 12, scale = 2, updatable = false)
    private BigDecimal annualCeilingPerBeneficiary;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "change_reason", length = 500, updatable = false)
    private String changeReason;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** Replaced before it started: its range is empty, so it never applies. */
    public boolean isCancelled() {
        return effectiveTo != null && effectiveTo.isBefore(effectiveFrom);
    }

    /** Whether this version applies on {@code day}. */
    public boolean coversDay(LocalDate day) {
        return !isCancelled() && !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
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
