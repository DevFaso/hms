package com.example.hms.model.pharmacy;

import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.enums.PharmacyPaymentStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
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

/**
 * Payment record for pharmacy checkout. Linked to a dispense event.
 */
@Entity
@Table(
    name = "pharmacy_payments",
    schema = "billing",
    indexes = {
        @Index(name = "idx_pp_dispense", columnList = "dispense_id"),
        @Index(name = "idx_pp_patient", columnList = "patient_id"),
        @Index(name = "idx_pp_hospital", columnList = "hospital_id"),
        @Index(name = "idx_pp_method", columnList = "payment_method")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"dispense", "patient", "hospital", "receivedByUser"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PharmacyPayment extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispense_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pp_dispense"))
    private Dispense dispense;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pp_patient"))
    private Patient patient;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pp_hospital"))
    private Hospital hospital;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30)
    private PharmacyPaymentMethod paymentMethod;

    @NotNull
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Size(max = 10)
    @Column(name = "currency", nullable = false, length = 10)
    @Builder.Default
    private String currency = "XOF";

    @Size(max = 120)
    @Column(name = "reference_number", length = 120)
    private String referenceNumber;

    /**
     * Billing plan PR 9 (V308): the operator of a Mobile Money payment, typed by
     * the cashier (no operator integration). Null on older rows and for every
     * other method ("Opérateur non précisé").
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "mobile_money_operator", length = 20)
    private com.example.hms.enums.MobileMoneyOperator mobileMoneyOperator;

    /**
     * QA D1032: the readable receipt number ({@code CODE-PH-YYYY-000001}),
     * allocated once when the payment is recorded and never recomputed, so a
     * reprint shows the number first printed. Rows recorded before V246 were
     * numbered by the migration in the order they were taken.
     */
    @Column(name = "receipt_number", length = 40)
    private String receiptNumber;

    /**
     * QA D4186 (r81, V421): the selling price of one unit and the line total when this payment was taken, so the
     * receipt can be opened again and reprinted with the prices first charged, never with today's list price.
     * Null on payments recorded before V421: their reprint shows no price line.
     */
    @Column(name = "charged_unit_price", precision = 12, scale = 2)
    private BigDecimal chargedUnitPrice;

    @Column(name = "charged_line_total", precision = 12, scale = 2)
    private BigDecimal chargedLineTotal;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "received_by", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pp_received"))
    private User receivedByUser;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    /** QA D404: VOIDED once the dispense it paid for is cancelled (V213). */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PharmacyPaymentStatus status = PharmacyPaymentStatus.POSTED;

    @Column(name = "voided_at")
    private java.time.LocalDateTime voidedAt;

    @Column(name = "voided_by")
    private java.util.UUID voidedBy;

    @Size(max = 255)
    @Column(name = "void_reason", length = 255)
    private String voidReason;

    /**
     * Billing plan PR 11 (V310, QA D2430): the key the portal generates once per payment dialog.
     * A retry or a second submit carrying it answers with this record; none is written again.
     * Unique per hospital when present.
     */
    @Column(name = "client_request_id")
    private java.util.UUID clientRequestId;

    /** Billing plan PR 10 (V309, QA D2672): the pharmacy cash session the money was taken in. */
    @Column(name = "cash_session_id")
    private java.util.UUID cashSessionId;
}
