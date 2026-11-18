package com.example.hms.model;

import com.example.hms.enums.PayerType;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Billing-money plan PR 6 (V305, scorecard M1): one payer of a hospital ("organisme payeur") - a mutuelle, an insurer,
 * an employer, a programme. The code is the hospital's own; the name is shown exactly as typed, never translated
 * (D-8). Its plans are {@link PayerPlan} rows. Maintained only by the hospital admin and the accountant of that
 * hospital (owner decision D-1), through {@code PayerCatalogueService}.
 */
@Entity
@Table(
    name = "payers",
    schema = "billing",
    uniqueConstraints = @UniqueConstraint(name = "uq_payer_code", columnNames = {"hospital_id", "code"})
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
// Identity is the id, as for every BaseEntity row; adding columns adds no equality.
@SuppressWarnings("java:S2160")
public class Payer extends BaseEntity implements TenantScoped {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    /** Upper case, trimmed; unique per hospital, inactive payers included. Never changes once created. */
    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "payer_type", nullable = false, length = 30)
    private PayerType payerType;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    @Column(name = "deactivated_by")
    private UUID deactivatedBy;

    @Column(name = "deactivation_reason", length = 500)
    private String deactivationReason;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

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
