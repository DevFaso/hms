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

@Entity
@Table(
    name = "platform_feature_flag_overrides",
    schema = "platform",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_feature_flag_override_key", columnNames = {"flag_key"})
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
}
