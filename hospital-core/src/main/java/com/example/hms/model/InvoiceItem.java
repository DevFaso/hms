package com.example.hms.model;

import com.example.hms.enums.ItemCategory;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

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
public class InvoiceItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "billing_invoice_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_item_invoice"))
    private BillingInvoice billingInvoice;

    @NotBlank
    @Column(nullable = false, length = 255)
    private String itemDescription;

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

    @PrePersist
    @PreUpdate
    public void validateAndCalculate() {
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
        if (relatedService != null && relatedService.getHospital() != null) {
            if (!Objects.equals(relatedService.getHospital().getId(), billingInvoice.getHospital().getId())) {
                throw new IllegalStateException("InvoiceItem.relatedService.hospital must match invoice.hospital");
            }
        }
    }
}
