package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "frontend_audit_events",
    schema = "support",
    indexes = {
        @Index(name = "idx_frontend_audit_event_type", columnList = "event_type"),
        @Index(name = "idx_frontend_audit_actor", columnList = "actor"),
        @Index(name = "idx_frontend_audit_occurred", columnList = "occurred_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class FrontendAuditEvent extends BaseEntity {

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(name = "actor", length = 255)
    private String actor;

    @Column(name = "metadata", length = 4000)
    private String metadata;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @PrePersist
    public void ensureOccurredAt() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
    }
}
