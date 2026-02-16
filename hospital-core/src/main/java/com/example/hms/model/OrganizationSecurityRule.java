package com.example.hms.model;

import com.example.hms.enums.SecurityRuleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.EqualsAndHashCode;

@Entity
@Table(
    name = "organization_security_rules",
    schema = "security",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_org_security_rule_code", columnNames = {"security_policy_id", "code"})
    },
    indexes = {
        @Index(name = "idx_org_rule_policy", columnList = "security_policy_id"),
        @Index(name = "idx_org_rule_active", columnList = "active"),
        @Index(name = "idx_org_rule_type", columnList = "rule_type")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class OrganizationSecurityRule extends BaseEntity {

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String name;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String code;

    @Size(max = 1000)
    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 50)
    private SecurityRuleType ruleType;

    @Column(name = "rule_value", length = 2000)
    private String ruleValue;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "security_policy_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_org_security_rule_policy"))
    private OrganizationSecurityPolicy securityPolicy;

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (code != null) code = code.trim().toUpperCase();
        if (name != null) name = name.trim();
        if (description != null) description = description.trim();
        if (ruleValue != null) ruleValue = ruleValue.trim();
    }

    @Override
    public String toString() {
        return "OrganizationSecurityRule{" +
            "id=" + getId() +
            ", name='" + name + '\'' +
            ", code='" + code + '\'' +
            ", ruleType=" + ruleType +
            ", active=" + active +
            ", priority=" + priority +
            '}';
    }
}
