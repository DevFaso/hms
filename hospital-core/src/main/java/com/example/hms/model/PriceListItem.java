package com.example.hms.model;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * Billing-money plan PR 2 (V302, scorecard M1): one entry of a hospital's price list ("grille tarifaire"). The code
 * is the hospital's own tariff code; the label is shown exactly as the hospital typed it, never translated (D-8).
 * Its prices are {@link PriceListPrice} versions. Maintained only by the hospital admin and the accountant of that
 * hospital (owner decision D-1, 2026-11-15), through {@code PriceListService}.
 */
@Entity
@Table(
    name = "price_list_items",
    schema = "billing",
    uniqueConstraints = @UniqueConstraint(name = "uq_price_list_item_code", columnNames = {"hospital_id", "code"}),
    indexes = @Index(name = "idx_price_list_item_hospital_category", columnList = "hospital_id, category")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
// Identity is the id, as for every BaseEntity row; adding columns adds no equality.
@SuppressWarnings("java:S2160")
public class PriceListItem extends BaseEntity implements TenantScoped {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    /** Upper case, trimmed; unique per hospital, inactive items included. Never changes once created. */
    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private PriceListCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", length = 40)
    private PriceListLinkType linkType;

    @Column(name = "link_key", length = 255)
    private String linkKey;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** When, by whom and why the item was deactivated; null while active. */
    @Column(name = "deactivated_at")
    private java.time.LocalDateTime deactivatedAt;

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
