package com.example.hms.model.pharmacy;

import com.example.hms.model.BaseEntity;
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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D2679 (V388): a witness's own confirmation that they are present for the controlled-drug movements one
 * colleague records at one hospital. Written only after the witness's password or authenticator code was
 * verified ({@code ControlledWitnessConfirmation}); a controlled movement naming a witness is recorded only
 * while such a row is unexpired. Rows are kept: they are the record that the second person acted.
 *
 * <p>Ids only (the witness, the performer, the hospital), the method and the two times. Never the secret.
 */
@Entity
@Table(
    name = "stock_witness_confirmations",
    schema = "clinical",
    indexes = @Index(name = "idx_stock_witness_confirmation_lookup",
        columnList = "witness_user_id, performed_by_user_id, hospital_id, expires_at")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class StockWitnessConfirmation extends BaseEntity {

    /** The colleague who confirmed, with their own factor. */
    @Column(name = "witness_user_id", nullable = false)
    private UUID witnessUserId;

    /** The person recording the movements the witness is present for. */
    @Column(name = "performed_by_user_id", nullable = false)
    private UUID performedByUserId;

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    /** TOTP or PASSWORD: what the witness confirmed with. */
    @Column(name = "method", nullable = false, length = 20)
    private String method;

    @Column(name = "confirmed_at", nullable = false)
    private LocalDateTime confirmedAt;

    /** After this the witness confirms again. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
