package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.UUID;

/**
 * Billing-money plan PR 10 (V309, QA D2325): what the cashier counted for one method (and mobile-money operator) at
 * one close of a session, beside what the session expected (money in - money out). A non-zero difference carries the
 * cashier's explanation. A reopened session closes again with a new round; earlier rounds are kept.
 */
@Entity
@Table(
    name = "cash_session_counts",
    schema = "billing",
    indexes = @Index(name = "idx_cash_count_session", columnList = "session_id,close_round")
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class CashSessionCount extends BaseEntity {

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "close_round", nullable = false)
    private Integer closeRound;

    /** A {@code PaymentMethod} name (a pharmacy method maps to the same names). */
    @Column(name = "method", nullable = false, length = 30)
    private String method;

    /** A {@code MobileMoneyOperator} name, for mobile money only. */
    @Column(name = "mobile_money_operator", length = 20)
    private String mobileMoneyOperator;

    @Column(name = "expected_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "counted_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal countedAmount;

    @Column(name = "difference", nullable = false, precision = 12, scale = 2)
    private BigDecimal difference;

    @Column(name = "explanation", length = 500)
    private String explanation;

    @Column(name = "counted_by", nullable = false)
    private UUID countedBy;
}
