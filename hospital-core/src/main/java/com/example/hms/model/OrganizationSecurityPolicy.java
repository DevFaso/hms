package com.example.hms.model;

import com.example.hms.enums.SecurityPolicyType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
    name = "organization_security_policies",
    schema = "security",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_org_security_policy_code", columnNames = {"organization_id", "code"})
    },
    indexes = {
        @Index(name = "idx_org_policy_organization", columnList = "organization_id"),
        @Index(name = "idx_org_policy_active", columnList = "active"),
        @Index(name = "idx_org_policy_type", columnList = "policy_type")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationSecurityPolicy extends BaseEntity {

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
    @Column(name = "policy_type", nullable = false, length = 50)
    private SecurityPolicyType policyType;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "enforce_strict", nullable = false)
    private boolean enforceStrict = false;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_org_security_policy_organization"))
    private Organization organization;

    @Builder.Default
    @OneToMany(mappedBy = "securityPolicy", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<OrganizationSecurityRule> rules = new HashSet<>();

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (code != null) code = code.trim().toUpperCase();
        if (name != null) name = name.trim();
        if (description != null) description = description.trim();
    }

    public void addRule(OrganizationSecurityRule rule) {
        if (rule == null) return;
        rules.add(rule);
        rule.setSecurityPolicy(this);
    }

    public void removeRule(OrganizationSecurityRule rule) {
        if (rule == null) return;
        rules.remove(rule);
        if (rule.getSecurityPolicy() == this) {
            rule.setSecurityPolicy(null);
        }
    }

    @Override
    public String toString() {
        return "OrganizationSecurityPolicy{" +
            "id=" + getId() +
            ", name='" + name + '\'' +
            ", code='" + code + '\'' +
            ", policyType=" + policyType +
            ", active=" + active +
            ", priority=" + priority +
            '}';
    }
}
