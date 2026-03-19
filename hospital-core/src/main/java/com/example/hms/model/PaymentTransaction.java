package com.example.hms.model;

import com.example.hms.enums.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "payment_transactions",
    schema = "billing",
    indexes = {
        @Index(name = "idx_pt_invoice", columnList = "invoice_id"),
        @Index(name = "idx_pt_payment_date", columnList = "payment_date"),
        @Index(name = "idx_pt_method", columnList = "payment_method")
    }
)
@Check(constraints = "amount > 0")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"invoice"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PaymentTransaction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pt_invoice"))
    private BillingInvoice invoice;

    @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2)
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @NotNull
    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30)
    private PaymentMethod paymentMethod;

    @Column(name = "reference_number", length = 120)
    private String referenceNumber;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "notes", length = 1024)
    private String notes;
}
