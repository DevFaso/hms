package com.example.hms.model.platform;

import com.example.hms.enums.platform.WebhookDeliveryStatus;
import com.example.hms.enums.platform.WebhookEventType;
import com.example.hms.model.BaseEntity;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * One outbound webhook delivery (Tier 2 item 45) — the outbox row, with
 * the instrument-outbox retry vocabulary (attempts / lastError /
 * lastAttemptAt, V119 precedent). The payload is a thin id-reference
 * JSON: no PHI ever rides in a webhook body, so the row needs no
 * encryption and can be shown to the partner that owns the endpoint.
 */
@Entity
@Table(
    name = "webhook_deliveries",
    schema = "platform",
    indexes = {
        @Index(name = "idx_webhook_deliveries_dispatch", columnList = "status, last_attempt_at"),
        @Index(name = "idx_webhook_deliveries_endpoint", columnList = "endpoint_id, created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = "endpoint")
public class WebhookDelivery extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_webhook_delivery_endpoint"))
    private WebhookEndpoint endpoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private WebhookEventType eventType;

    /** Thin id-reference JSON — event, resourceType, resourceId, occurredAt. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    /** The receiver's HTTP status on the last attempt, when one came back. */
    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
