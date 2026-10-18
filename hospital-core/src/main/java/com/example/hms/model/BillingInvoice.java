package com.example.hms.model;

import com.example.hms.enums.InvoiceStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Check;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
    name = "billing_invoices",
    schema = "billing",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_bi_hospital_invoice_number", columnNames = {"hospital_id", "invoice_number"})
    },
    indexes = {
        @Index(name = "idx_bi_patient", columnList = "patient_id"),
        @Index(name = "idx_bi_hospital", columnList = "hospital_id")
    }
)
@Check(constraints = "amount_paid <= total_amount")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"patient", "hospital", "encounter", "invoiceItems"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class BillingInvoice extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_bi_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_bi_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id",
        foreignKey = @ForeignKey(name = "fk_bi_encounter"))
    private Encounter encounter;

    @NotBlank
    @Column(name = "invoice_number", nullable = false, length = 50)
    private String invoiceNumber;

    @NotNull
    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @NotNull
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
    @Column(name = "amount_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountPaid;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private InvoiceStatus status;

    @Column(name = "notes", length = 2048)
    private String notes;

    @OneToMany(mappedBy = "billingInvoice",
        fetch = FetchType.LAZY,
        cascade = CascadeType.ALL,
        orphanRemoval = true)
    @Builder.Default

    private Set<InvoiceItem> invoiceItems = new HashSet<>();

    // Spring Data Auditing — ensure @EnableJpaAuditing + AuditorAware<UUID> bean exists
    @CreatedBy
    @Column(name = "created_by")
    private java.util.UUID createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private java.util.UUID updatedBy;

    /**
     * The key the portal sends with a create (QA D94): a retry or a double
     * submit carrying the same key returns the invoice already created.
     * Unique per hospital when present (V190).
     */
    @Column(name = "client_request_id")
    private java.util.UUID clientRequestId;

    /* ---------- Helpers ---------- */

    public void addItem(InvoiceItem item) {
        item.setBillingInvoice(this);
        invoiceItems.add(item);
        recomputeTotals();
    }

    public void removeItem(InvoiceItem item) {
        invoiceItems.remove(item);
        item.setBillingInvoice(null);
        recomputeTotals();
    }

    /**
     * Total = sum of the lines held in memory. Used only by {@link #addItem}
     * and {@link #removeItem}; the service recomputes from the database
     * ({@code BillingInvoiceService.recomputeAndPersistTotals}), and the
     * lifecycle callbacks no longer call it (QA D72).
     */
    private void recomputeTotals() {
        BigDecimal sum = invoiceItems.stream()
            .map(BillingInvoice::lineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        totalAmount = sum.setScale(2, RoundingMode.HALF_UP);
        if (amountPaid == null) amountPaid = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (amountPaid.compareTo(BigDecimal.ZERO) == 0 && totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            status = InvoiceStatus.DRAFT;
        } else if (amountPaid.compareTo(totalAmount) >= 0 && totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            status = InvoiceStatus.PAID;
        } else if (status == null) {
            status = InvoiceStatus.DRAFT;
        }
    }

    private static BigDecimal lineTotal(InvoiceItem item) {
        if (item.getTotalPrice() != null) return item.getTotalPrice();
        if (item.getUnitPrice() != null && item.getQuantity() != null) {
            return item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        }
        return BigDecimal.ZERO;
    }

    @PrePersist
    public void prePersist() {
        if (invoiceDate == null) invoiceDate = LocalDate.now();
        if (dueDate == null)    dueDate = invoiceDate.plusDays(30);
        if (amountPaid == null) amountPaid = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (totalAmount == null) totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (status == null) status = InvoiceStatus.DRAFT;
        // QA D72: no recompute here. It summed the in-memory line collection,
        // which is empty on a create and unloaded on an update, so it wrote
        // 0.00 over every total. The service owns the total.
        if (dueDate.isBefore(invoiceDate)) {
            throw new IllegalStateException("dueDate cannot be before invoiceDate");
        }
    }

    @PreUpdate
    public void preUpdate() {
        if (dueDate.isBefore(invoiceDate)) {
            throw new IllegalStateException("dueDate cannot be before invoiceDate");
        }
    }
}
