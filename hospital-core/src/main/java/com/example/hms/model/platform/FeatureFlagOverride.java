package com.example.hms.model.platform;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(
    name = "platform_feature_flag_overrides",
    schema = "platform",
    uniqueConstraints = {
        // MVP-7b: composite UNIQUE so the same flag key can hold a
        // global override (organization_id NULL) plus any number of
        // per-tenant overrides (one per organization). Postgres treats
        // NULLs as distinct in uniqueness comparisons, so the global
        // row never collides with itself.
        @UniqueConstraint(
            name = "uq_feature_flag_override_key_per_org",
            columnNames = {"flag_key", "organization_id"})
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class FeatureFlagOverride extends BaseEntity {

    @NotBlank
    @Size(max = 120)
    @Column(name = "flag_key", nullable = false, length = 120)
    private String flagKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Size(max = 255)
    @Column(name = "description", length = 255)
    private String description;

    @Size(max = 120)
    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    /**
     * MVP-7b: when null, this row is a *global* override that applies
     * to every tenant; when set, the row narrows to the named tenant
     * only. The application layer composes
     * (default → global override → tenant override) so a tenant
     * override beats a global override for that tenant only.
     */
    @Column(name = "organization_id")
    private UUID organizationId;
}
