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
    name = "platform_global_settings",
    schema = "platform",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_global_setting_key", columnNames = {"setting_key"})
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class GlobalSetting extends BaseEntity {

    @NotBlank
    @Size(max = 120)
    @Column(name = "setting_key", nullable = false, length = 120)
    private String settingKey;

    @Size(max = 2000)
    @Column(name = "setting_value", length = 2000)
    private String settingValue;

    @Size(max = 60)
    @Column(name = "category", length = 60)
    private String category;

    @Size(max = 255)
    @Column(name = "description", length = 255)
    private String description;

    @Size(max = 120)
    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
