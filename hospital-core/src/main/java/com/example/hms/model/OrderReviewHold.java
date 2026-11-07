package com.example.hms.model;

import com.example.hms.enums.OrderReviewStatus;
import com.example.hms.enums.OrderReviewType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An order outstanding when a death was recorded or disputed (user ruling, round 36; NOT clinically approved). Snapshot
 * in that event's own transaction, and never derived from the death date, which may itself be corrected. An authorized
 * prescriber at the order's hospital continues, replaces or stops it; no administrative correction clears it.
 *
 * <p>Plain ids and no associations: {@code OrderReviewHoldGuard} asks it during any use of an order.
 */
@Entity
@Table(
    name = "order_review_holds",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_order_review_patient", columnList = "patient_id, hospital_id"),
        @Index(name = "idx_order_review_dispute", columnList = "dispute_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class OrderReviewHold extends BaseEntity {

    @Column(name = "death_record_id", nullable = false)
    private UUID deathRecordId;

    /** Null for a row from the death event, until a dispute opens at this hospital. */
    @Column(name = "dispute_id")
    private UUID disputeId;

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 30)
    private OrderReviewType orderType;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /** The order's version at the snapshot. */
    @Column(name = "order_version", nullable = false)
    private Long orderVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderReviewStatus status;

    /** The version the prescriber actually reviewed. */
    @Column(name = "reviewed_order_version")
    private Long reviewedOrderVersion;

    @Column(name = "decided_by_id")
    private UUID decidedById;

    @Size(max = 255)
    @Column(name = "decided_by_name", length = 255)
    private String decidedByName;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Size(max = 1000)
    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "replacement_order_id")
    private UUID replacementOrderId;
}
