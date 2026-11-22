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

    /**
     * QA D3450 (r70, V361): an IMAGING line is an exam of the hospital's imaging catalogue. Its body region, for
     * the order form.
     */
    @Column(name = "imaging_body_region", length = 150)
    private String imagingBodyRegion;

    /**
     * QA D3450 (V361): whether this exam may reveal a pregnancy, as the hospital admin or a radiologist decided:
     * TRUE keeps it from every family proxy, FALSE clears it, NULL is undecided and read by
     * {@code PregnancyRevealingStudyRule} (the exam's name, then the fail-closed rule).
     */
    @Column(name = "may_reveal_pregnancy")
    private Boolean mayRevealPregnancy;

    /** Who decided {@link #mayRevealPregnancy}, and when; null while nobody has. */
    @Column(name = "may_reveal_pregnancy_set_by")
    private UUID mayRevealPregnancySetBy;

    @Column(name = "may_reveal_pregnancy_set_at")
    private java.time.LocalDateTime mayRevealPregnancySetAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /**
     * QA D3450 (r70): the pregnancy flag is a decision about a NAMED exam. Renamed, moved to another category or
     * relinked (another modality), the item is another exam: the decision goes back to « not decided », where the
     * exam is read by its words and held by the fail-closed rule, until the hospital admin or a radiologist decides
     * again. Held here, in the setters, so that no writer of an item (the form, an import, a link by code) can
     * rename a cleared « Radiographie du thorax » into an obstetric ultrasound and keep it cleared.
     */
    public void setLabel(String label) {
        if (this.label != null && (label == null || !this.label.trim().equals(label.trim()))) {
            clearPregnancyDecision();
        }
        this.label = label;
    }

    public void setCategory(PriceListCategory category) {
        if (this.category != null && this.category != category) {
            clearPregnancyDecision();
        }
        this.category = category;
    }

    public void setLinkKey(String linkKey) {
        if (!java.util.Objects.equals(this.linkKey, linkKey)) {
            clearPregnancyDecision();
        }
        this.linkKey = linkKey;
    }

    private void clearPregnancyDecision() {
        this.mayRevealPregnancy = null;
        this.mayRevealPregnancySetBy = null;
        this.mayRevealPregnancySetAt = null;
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
