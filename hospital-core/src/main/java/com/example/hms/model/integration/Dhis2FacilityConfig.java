package com.example.hms.model.integration;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 * Per-hospital DHIS2 endpoint + auth pointer for ADX exports.
 *
 * <p>One row per hospital (UNIQUE on {@code hospital_id}). Auth secrets
 * are NEVER stored: {@link #authSecretEnvVar} holds the name of the
 * environment variable the {@code DhisHttpClient} resolves at push time.
 */
@Entity
@Table(
    name = "dhis2_facility_config",
    schema = "integration",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_dhis2_facility_config_hospital",
            columnNames = "hospital_id")
    },
    indexes = {
        @Index(name = "idx_dhis2_facility_config_active", columnList = "hospital_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "hospital")
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Dhis2FacilityConfig extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_dhis2_facility_config_hospital"))
    private Hospital hospital;

    @NotBlank
    @Size(max = 512)
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_mode", nullable = false, length = 16)
    private Dhis2AuthMode authMode;

    /**
     * Name of the environment variable holding the secret. The value
     * is resolved at push time and never persisted.
     */
    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
        message = "auth_secret_env_var must be an upper-snake-case environment-variable name")
    @Column(name = "auth_secret_env_var", nullable = false, length = 128)
    private String authSecretEnvVar;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "default_period_type", nullable = false, length = 16)
    private Dhis2PeriodType defaultPeriodType;

    @Size(max = 11)
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 dataset UID must be 11 characters, alphanumeric, leading letter")
    @Column(name = "default_dataset_uid", length = 11)
    private String defaultDatasetUid;

    @Column(name = "last_export_at")
    private LocalDateTime lastExportAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
