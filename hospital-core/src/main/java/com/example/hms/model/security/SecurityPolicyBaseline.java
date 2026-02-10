package com.example.hms.model.security;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "security_policy_baselines",
    schema = "governance",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_security_policy_baseline_version", columnNames = {"baseline_version"})
    },
    indexes = {
        @Index(name = "idx_security_policy_baselines_created_at", columnList = "created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class SecurityPolicyBaseline extends BaseEntity {

    @Column(name = "baseline_version", nullable = false, length = 80)
    private String baselineVersion;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(length = 1000)
    private String summary;

    @Column(name = "enforcement_level", nullable = false, length = 60)
    private String enforcementLevel;

    @Column(name = "policy_count", nullable = false)
    private Integer policyCount;

    @Column(name = "control_objectives_json", columnDefinition = "TEXT")
    private String controlObjectivesJson;

    @Column(name = "created_by", length = 120)
    private String createdBy;
}
