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
}
