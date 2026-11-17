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

import java.time.LocalDateTime;

/**
 * One mail the self-service email change sent to a new address: the code,
 * or the notice to the holder of an address already in use (V166). The
 * per-address send limit counts these, whatever each account's pending
 * change says now, so re-targeting or cancelling cannot reset the count.
 *
 * <p>Only a SHA-256 hash of the normalised address is stored, never the
 * address: enough to count, nothing to read back.
 */
@Entity
@Table(
    name = "email_change_sends",
    schema = "\"security\"",
    indexes = @Index(name = "idx_email_change_sends_address", columnList = "address_hash, sent_at")
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class EmailChangeSend extends BaseEntity {

    /** Hex SHA-256 of the normalised address ({@code EmailAddresses.hash}). */
    @Column(name = "address_hash", nullable = false, length = 64)
    private String addressHash;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;
}
