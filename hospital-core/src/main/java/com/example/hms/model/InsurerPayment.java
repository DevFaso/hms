package com.example.hms.model;

import com.example.hms.enums.InsurerPaymentMethod;
import com.example.hms.enums.InsurerPaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Insurer claims plan PR 3 (V456, QA D3983): one remittance of a payer: the amount, the day it was received, how,
 * and its reference. Recorded by the accountant or the hospital admin (owner decision of 2026-11-26), outside any
 * cash session: it is never a till movement. The amount is never rewritten; an entry made in error is REVERSED,
 * with a reason, by another person than the one who recorded it.
 */
@Entity
@Table(
    name = "insurer_payments",
    schema = "billing",
    indexes = @Index(name = "idx_insurer_payment_hospital_payer", columnList = "hospital_id,payer_id")
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InsurerPayment extends BaseEntity {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "payer_id", nullable = false, updatable = false)
    private UUID payerId;

    /** Whole francs, more than 0. Never updated. */
    @Column(name = "amount", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "received_date", nullable = false, updatable = false)
    private LocalDate receivedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20, updatable = false)
    private InsurerPaymentMethod method;

    /** The bank's, the cheque's or the operator's reference of the remittance. */
    @Column(name = "reference", nullable = false, length = 120, updatable = false)
    private String reference;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InsurerPaymentStatus status;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @Column(name = "reversed_by")
    private UUID reversedBy;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    /** A retried request carrying the same key answers with the payment already recorded (unique per hospital). */
    @Column(name = "client_request_id", nullable = false, updatable = false)
    private UUID clientRequestId;
}
