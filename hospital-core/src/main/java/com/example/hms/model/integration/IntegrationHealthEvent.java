package com.example.hms.model.integration;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per recorded probe / call outcome on an integration
 * (MVP-c batch — MVP-3b time-series history).
 *
 * <p>Lives alongside {@link IntegrationHealthSnapshot}: the snapshot is
 * the always-fresh "current health" view, this event log is the
 * append-only history powering the 24h sparkline + per-org drilldown
 * timeline. Every {@code IntegrationHealthRecorder.recordSuccess /
 * recordFailure} call inserts one row here in addition to the
 * existing snapshot upsert.
 */
@Entity
@Table(name = "integration_health_event", schema = "clinical")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class IntegrationHealthEvent {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "integration_id", nullable = false, length = 120)
    private String integrationId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IntegrationHealthStatus status;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @PrePersist
    private void touch() {
        if (recordedAt == null) {
            recordedAt = LocalDateTime.now();
        }
    }
}
