package com.example.hms.model.integration;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Organization;
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
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Per-(integration, organization) rolled-up health snapshot consumed by the
 * super-admin Integration Health Console (MVP-3 — see docs/super-admin-gaps.md).
 *
 * <p>Upserted by {@code IntegrationHealthRecorder} after every successful or
 * failed integration call. The {@code @Version} guard protects the rare race
 * where two callers update the same row at the same time. The
 * {@code organization_id} column is intentionally nullable so platform-wide
 * descriptors that have not yet been org-scoped (e.g. the EHR / Billing /
 * Inventory adapters today) can still register a single snapshot row keyed
 * on {@code (integration_id, NULL)}.
 */
@Entity
@Table(
    name = "integration_health_snapshots",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_integration_health_integration_org",
            columnList = "integration_id,organization_id"),
        @Index(name = "idx_integration_health_org_status",
            columnList = "organization_id,last_status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"organization"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class IntegrationHealthSnapshot extends BaseEntity {

    @NotBlank
    @Size(max = 100)
    @Column(name = "integration_id", nullable = false, length = 100)
    private String integrationId;

    /** Nullable so platform-wide descriptors can record a single row. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id",
        foreignKey = @ForeignKey(name = "fk_integration_health_org"))
    private Organization organization;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", nullable = false, length = 32)
    @Builder.Default
    private IntegrationHealthStatus lastStatus = IntegrationHealthStatus.NO_HISTORY;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "last_failure_at")
    private LocalDateTime lastFailureAt;

    @Size(max = 1000)
    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @Column(name = "success_count_24h", nullable = false)
    @Builder.Default
    private Integer successCount24h = 0;

    @Column(name = "failure_count_24h", nullable = false)
    @Builder.Default
    private Integer failureCount24h = 0;

    @Column(name = "counts_window_started_at")
    private LocalDateTime countsWindowStartedAt;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;
}
