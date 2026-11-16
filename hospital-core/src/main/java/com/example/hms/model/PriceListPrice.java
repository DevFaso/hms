package com.example.hms.model;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Billing-money plan PR 2 (V302): one price version of a {@link PriceListItem}, in force from
 * {@link #effectiveFrom} to {@link #effectiveTo} (inclusive; open-ended while null). Insert-only: no endpoint edits
 * or deletes a version. The one update is {@code PriceListService} setting {@link #effectiveTo}, under a lock on the
 * item, when a newer version starts, or when a version that has not started yet is replaced (then
 * {@code effectiveTo = effectiveFrom - 1}: never in force, kept in the history).
 *
 * <p>An invoice line copies its unit price when it is posted, so a new version never rewrites a posted line (D-4).
 */
@Entity
@Table(
    name = "price_list_prices",
    schema = "billing",
    indexes = {
        @Index(name = "idx_price_list_price_item", columnList = "item_id, effective_from"),
        @Index(name = "idx_price_list_price_hospital", columnList = "hospital_id")
    }
)
@Check(constraints = "unit_price >= 0")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "item")
// Identity is the id, as for every BaseEntity row; adding columns adds no equality.
@SuppressWarnings("java:S2160")
public class PriceListPrice extends BaseEntity implements TenantScoped {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false, updatable = false,
        foreignKey = @ForeignKey(name = "fk_price_list_price_item"))
    private PriceListItem item;

    /** Whole francs. */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "change_reason", length = 500, updatable = false)
    private String changeReason;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** The CSV import that wrote this version (PR 4); null when entered on the screen. */
    @Column(name = "import_id", updatable = false)
    private UUID importId;

    /** Replaced before it started: its range is empty, so it is never in force. */
    public boolean isReplaced() {
        return effectiveTo != null && effectiveTo.isBefore(effectiveFrom);
    }

    /** Whether this version prices {@code day}. */
    public boolean coversDay(LocalDate day) {
        return !isReplaced() && !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
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
