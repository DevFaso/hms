package com.example.hms.model.pharmacy;

import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.enums.PharmacySaleStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * P-07: OTC (over-the-counter) walk-in cash sale at a pharmacy.
 *
 * <p>This is intentionally distinct from {@code Dispense} (which is tied to a
 * prescription) and {@code PharmacyPayment} (which is the payment record for an
 * insured dispense). A {@code PharmacySale} captures cash transactions that have
 * no clinical prescription attached — e.g. a walk-in customer buying OTC
 * paracetamol — so that revenue and stock movement are still tracked.
 *
 * <p>Patient is nullable: an anonymous walk-in is permitted and common.
 */
@Entity
@Table(
    name = "pharmacy_sales",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_psale_pharmacy", columnList = "pharmacy_id"),
        @Index(name = "idx_psale_hospital", columnList = "hospital_id"),
        @Index(name = "idx_psale_patient", columnList = "patient_id"),
        @Index(name = "idx_psale_sold_by", columnList = "sold_by"),
        @Index(name = "idx_psale_status", columnList = "status"),
        @Index(name = "idx_psale_sale_date", columnList = "sale_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"pharmacy", "hospital", "patient", "soldByUser", "lines"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PharmacySale extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pharmacy_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_psale_pharmacy"))
    private Pharmacy pharmacy;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_psale_hospital"))
    private Hospital hospital;

    /** Nullable: walk-in OTC sales need not be linked to a registered patient. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id",
        foreignKey = @ForeignKey(name = "fk_psale_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sold_by", nullable = false,
        foreignKey = @ForeignKey(name = "fk_psale_sold_by"))
    private User soldByUser;

    @NotNull
    @Column(name = "sale_date", nullable = false)
    private LocalDateTime saleDate;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30)
    private PharmacyPaymentMethod paymentMethod;

    @NotNull
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Size(max = 10)
    @Column(name = "currency", nullable = false, length = 10)
    @Builder.Default
    private String currency = "XOF";

    @Size(max = 120)
    @Column(name = "reference_number", length = 120)
    private String referenceNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PharmacySaleStatus status = PharmacySaleStatus.COMPLETED;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SaleLine> lines = new ArrayList<>();

    public void addLine(SaleLine line) {
        line.setSale(this);
        this.lines.add(line);
    }
}
