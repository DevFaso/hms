package com.example.hms.model;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * Billing-money plan PR 6 (V305): one plan ("formule") of a {@link Payer}: the default coverage rate (whole percent),
 * an optional ceiling per invoice and per beneficiary per calendar year (whole francs), and the days it is offered
 * ({@link #effectiveFrom} .. {@link #effectiveTo}, inclusive; open-ended while null; {@code effectiveTo =
 * effectiveFrom - 1} marks a plan withdrawn before it started). Its exceptions are {@link PayerPlanRule} rows. A
 * patient's coverage names one through {@code PatientInsurance.payerPlanId}. Nothing reads it at invoicing yet (PR 7).
 */
@Entity
@Table(
    name = "payer_plans",
    schema = "billing",
    uniqueConstraints = @UniqueConstraint(name = "uq_payer_plan_code", columnNames = {"payer_id", "code"}),
    indexes = @Index(name = "idx_payer_plan_hospital", columnList = "hospital_id")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
// Identity is the id, as for every BaseEntity row; adding columns adds no equality.
@SuppressWarnings("java:S2160")
public class PayerPlan extends BaseEntity implements TenantScoped {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "payer_id", nullable = false, updatable = false)
    private UUID payerId;

    /** Upper case, trimmed; unique per payer. Never changes once created. */
    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** 0 to 100. */
    @Column(name = "default_coverage_percent", nullable = false)
    private Integer defaultCoveragePercent;

    /** Whole francs, more than 0; null when the plan has no ceiling per invoice. */
    @Column(name = "per_invoice_ceiling", precision = 12, scale = 2)
    private BigDecimal perInvoiceCeiling;

    /** Whole francs, more than 0, per beneficiary and calendar year (D-13); null when none. */
    @Column(name = "annual_ceiling_per_beneficiary", precision = 12, scale = 2)
    private BigDecimal annualCeilingPerBeneficiary;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Withdrawn before it started: its range is empty, so it never applies. */
    public boolean isWithdrawn() {
        return effectiveTo != null && effectiveTo.isBefore(effectiveFrom);
    }

    /** Whether the plan is offered on {@code day}. */
    public boolean coversDay(LocalDate day) {
        return !isWithdrawn() && !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /** Whether the plan stopped before {@code day} (or was withdrawn): nothing new is linked to it. */
    public boolean endedBefore(LocalDate day) {
        return isWithdrawn() || (effectiveTo != null && effectiveTo.isBefore(day));
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
