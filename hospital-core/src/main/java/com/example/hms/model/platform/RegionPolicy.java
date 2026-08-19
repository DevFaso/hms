package com.example.hms.model.platform;

import com.example.hms.enums.OrganizationRegion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-region policy overrides (MVP-c batch — MVP-9c).
 *
 * <p>Keyed on the {@link OrganizationRegion} enum code. Each row carries
 * optional overrides — null means "fall back to the global policy":
 *
 * <ul>
 *   <li>{@code retentionDays} — null falls back to the global retention.
 *   <li>{@code defaultExportFormat} — null falls back to {@code STANDARD};
 *       GDPR-tagged regions (EU) seed with {@code GDPR_PORTABILITY}.
 *   <li>{@code targetDeploymentUrl} — when non-null, new tenants in this
 *       region are provisioned on the named deployment via
 *       {@code TenantProvisioningClient}. Today: stubbed for ops to wire
 *       up the actual remote provisioning hook.
 * </ul>
 *
 * <p>Seeded for every {@link OrganizationRegion} value by V86 with NULL
 * overrides so the resolver never sees a missing row.
 */
@Entity
@Table(name = "region_policy", schema = "platform")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "region")
public class RegionPolicy {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "region", nullable = false, length = 32)
    private OrganizationRegion region;

    @PositiveOrZero
    @Column(name = "retention_days")
    private Integer retentionDays;

    @Size(max = 32)
    @Column(name = "default_export_format", length = 32)
    private String defaultExportFormat;

    @Size(max = 255)
    @Column(name = "target_deployment_url", length = 255)
    private String targetDeploymentUrl;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Size(max = 255)
    @Column(name = "updated_by", nullable = false, length = 255)
    @Builder.Default
    private String updatedBy = "system";

    @PrePersist
    @PreUpdate
    private void touch() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }
}
