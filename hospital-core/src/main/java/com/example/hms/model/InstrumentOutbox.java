package com.example.hms.model;

import com.example.hms.enums.InstrumentOutboxStatus;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "instrument_outbox",
    schema = "lab",
    indexes = {
        @Index(name = "idx_instrument_outbox_order",  columnList = "lab_order_id"),
        @Index(name = "idx_instrument_outbox_status", columnList = "status")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"labOrder"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InstrumentOutbox extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lab_order_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_instrument_outbox_order"))
    private LabOrder labOrder;

    /** HL7v2 message type, e.g. {@code OML^O21} or {@code ORU^R01}. */
    @NotBlank
    @Column(name = "message_type", nullable = false, length = 20)
    private String messageType;

    /** Full HL7v2 message string. */
    @NotBlank
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private InstrumentOutboxStatus status = InstrumentOutboxStatus.PENDING;

    /** Timestamp when the message was delivered to the instrument or middleware. */
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /**
     * How many transmissions have been tried (V119).
     *
     * <p>A permanently unreachable analyser must stop being retried forever and
     * start being visible instead — an outbox that retries silently until the
     * heat death of the universe looks identical to one that is working.
     */
    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    /**
     * Why the last attempt failed. {@code status = ERROR} with no reason is a
     * dead end for whoever has to fix the interface at 3am.
     */
    @Column(name = "last_error", length = 2000)
    private String lastError;

    /** What the retry backoff is measured from. */
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;
}
