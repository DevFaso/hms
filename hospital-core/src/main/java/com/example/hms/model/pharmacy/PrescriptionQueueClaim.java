package com.example.hms.model.pharmacy;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * G13: who is preparing a prescription on the pharmacy work queue (V179).
 *
 * <p>Advisory: it coordinates people and guards no data. One row per
 * prescription; whether it is still active is computed from
 * {@link #claimedAt} and the configured TTL, so there is no expiry column.
 * Claiming over an expired row, renewing and taking over update this row in
 * place; a release deletes it.
 *
 * <p>{@code Prescription} has no inverse mapping on purpose: a non-owning
 * {@code @OneToOne} cannot be lazy, and every prescription read would load
 * the claim. The claim is reached only through its repository.
 */
@Entity
@Table(
    name = "prescription_queue_claims",
    schema = "clinical",
    uniqueConstraints = @UniqueConstraint(name = "uq_rx_queue_claim_prescription",
        columnNames = "prescription_id"),
    indexes = @Index(name = "idx_rx_queue_claim_user", columnList = "claimed_by, claimed_at")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"prescription", "claimedBy"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PrescriptionQueueClaim extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescription_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_rx_queue_claim_prescription"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Prescription prescription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claimed_by", nullable = false,
        foreignKey = @ForeignKey(name = "fk_rx_queue_claim_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User claimedBy;

    @Column(name = "claimed_at", nullable = false)
    private LocalDateTime claimedAt;
}
