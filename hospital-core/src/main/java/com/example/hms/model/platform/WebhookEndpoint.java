package com.example.hms.model.platform;

import com.example.hms.enums.platform.WebhookEndpointStatus;
import com.example.hms.enums.platform.WebhookEventType;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.HashSet;
import java.util.Set;

/**
 * One outbound webhook endpoint a hospital registered (Tier 2 item 45):
 * where HMS POSTs a thin, HMAC-signed notification when a subscribed
 * event occurs. Revoked, never deleted — the delivery history hangs off
 * the row.
 *
 * <p>{@code secret} signs every delivery (HMAC-SHA256 over
 * {@code timestamp.body}); it is encrypted at rest and excluded from
 * {@code toString()} — a credential must not reach a log line.
 */
@Entity
@Table(
    name = "webhook_endpoints",
    schema = "platform",
    indexes = @Index(name = "idx_webhook_endpoints_hospital", columnList = "hospital_id, status")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"hospital", "secret", "subscribedEvents"})
public class WebhookEndpoint extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_webhook_endpoint_hospital"))
    private Hospital hospital;

    /** HTTPS only, public hosts only — WebhookUrlValidator gates writes. */
    @Size(max = 500)
    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Size(max = 255)
    @Column(name = "description", length = 255)
    private String description;

    /** The HMAC signing secret. Shown once at registration/rotation. */
    @Column(name = "secret", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String secret;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private WebhookEndpointStatus status = WebhookEndpointStatus.ACTIVE;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "webhook_endpoint_events", schema = "platform",
        joinColumns = @JoinColumn(name = "endpoint_id",
            foreignKey = @ForeignKey(name = "fk_webhook_event_endpoint")))
    @Column(name = "event_type", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<WebhookEventType> subscribedEvents = new HashSet<>();

    /**
     * Terminal delivery failures in a row; reset on any success. At the
     * configured ceiling the endpoint flips to DISABLED_FAILURES — a dead
     * receiver must not accumulate an unbounded queue.
     */
    @Column(name = "consecutive_failures", nullable = false)
    @Builder.Default
    private int consecutiveFailures = 0;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
