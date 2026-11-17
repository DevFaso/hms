package com.example.hms.model;

import com.example.hms.enums.InvoiceLinePriceSource;
import com.example.hms.enums.ItemCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "invoice_items", schema = "billing",
    indexes = {
        @Index(name = "idx_item_invoice", columnList = "billing_invoice_id"),
        @Index(name = "idx_item_assignment", columnList = "assignment_id"),
        @Index(name = "idx_item_service", columnList = "related_service_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"billingInvoice", "assignment", "relatedService"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InvoiceItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "billing_invoice_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_item_invoice"))
    private BillingInvoice billingInvoice;

    @NotBlank
    @Column(nullable = false, length = 255)
    private String itemDescription;

    /**
     * QA D1901: when the server wrote {@link #itemDescription} (the default
     * consultation, the bed days, a transfusion, a delivery), its message code
     * and arguments (V301), so each reader sees it in their own language;
     * {@link #itemDescription} keeps the French copy. Null for a line a person
     * typed or a hospital's own service name, shown as written.
     */
    @Column(name = "text_code", length = 120)
    private String textCode;

    @Column(name = "text_args", columnDefinition = "TEXT")
    private String textArgs;

    @NotNull @Positive
    @Column(nullable = false)
    private Integer quantity;

    @Builder.Default
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "item_category", nullable = false, length = 50)
    private ItemCategory itemCategory = ItemCategory.GENERAL;

    @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_item_assignment"))
    private UserRoleHospitalAssignment assignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_service_id",
        foreignKey = @ForeignKey(name = "fk_item_service"))
    private Treatment relatedService;

    /**
     * Billing-money plan PR 3 (V303): the price-list item and the version whose unit price this line copied when
     * it was posted. Plain ids (the FKs are in the schema): a later price never rewrites the line (D-4), and
     * nothing here loads the catalogue.
     */
    @Column(name = "price_list_item_id")
    private UUID priceListItemId;

    @Column(name = "price_list_price_id")
    private UUID priceListPriceId;

    /** Where {@link #unitPrice} came from. A line typed at the till is MANUAL. */
    @Builder.Default
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", nullable = false, length = 20)
    private InvoiceLinePriceSource priceSource = InvoiceLinePriceSource.MANUAL;

    /** The tariff's unit price when a cashier overrode it on a draft line (D-2); null otherwise. */
    @Column(name = "tariff_unit_price", precision = 12, scale = 2)
    private BigDecimal tariffUnitPrice;

    /** The cashier's reason for that override, as typed. */
    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    /**
     * QA D72: this ran on update only, so a NEW line reached the insert with
     * no total and bean validation refused it ("totalPrice must not be null").
     */
    @PrePersist
    @PreUpdate
    public void validateAndCalculate() {
        if (priceSource == null) {
            priceSource = InvoiceLinePriceSource.MANUAL;
        }
        // If linked to a service and unitPrice is not set, default from service
        if (relatedService != null && unitPrice == null) {
            unitPrice = relatedService.getPrice();
        }
        if (unitPrice == null || quantity == null) {
            throw new IllegalStateException("unitPrice and quantity are required");
        }
        totalPrice = unitPrice
            .multiply(BigDecimal.valueOf(quantity))
            .setScale(2, RoundingMode.HALF_UP);

        // Integrity: assignment/hospital must match invoice hospital
        if (billingInvoice == null || assignment == null || assignment.getHospital() == null
            || billingInvoice.getHospital() == null
            || !Objects.equals(assignment.getHospital().getId(), billingInvoice.getHospital().getId())) {
            throw new IllegalStateException("InvoiceItem.assignment.hospital must match invoice.hospital");
        }
        // If relatedService is set, its hospital must also match invoice hospital
        if (relatedService != null && relatedService.getHospital() != null
                && !Objects.equals(relatedService.getHospital().getId(), billingInvoice.getHospital().getId())) {
            throw new IllegalStateException("InvoiceItem.relatedService.hospital must match invoice.hospital");
        }
    }
}
