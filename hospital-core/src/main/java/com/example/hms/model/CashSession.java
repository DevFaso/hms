package com.example.hms.model;

import com.example.hms.enums.CashSessionStatus;
import com.example.hms.enums.CashTill;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Billing-money plan PR 10 (V309; QA D2325, D2326, D2672, D2714): one cashier's till for one business day at one
 * hospital. Every money movement names the session it was taken in; the close counts each method against what the
 * session expects, and a closed session takes no new movement until a supervisor reopens it.
 */
@Entity
@Table(
    name = "cash_sessions",
    schema = "billing",
    uniqueConstraints = @UniqueConstraint(name = "uq_cash_session_day",
        columnNames = {"hospital_id", "cashier_user_id", "till", "business_date"}),
    indexes = @Index(name = "idx_cash_session_hospital_day", columnList = "hospital_id,business_date")
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class CashSession extends BaseEntity {

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "cashier_user_id", nullable = false)
    private UUID cashierUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "till", nullable = false, length = 20)
    private CashTill till;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CashSessionStatus status;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    /** How many times the session was closed; each close keeps its own counts. */
    @Column(name = "close_round", nullable = false)
    @Builder.Default
    private Integer closeRound = 0;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "reopened_by")
    private UUID reopenedBy;

    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @Column(name = "reopen_reason", length = 500)
    private String reopenReason;
}
