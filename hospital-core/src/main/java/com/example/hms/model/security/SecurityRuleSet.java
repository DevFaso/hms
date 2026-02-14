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
    name = "security_rule_sets",
    schema = "governance",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_security_rule_set_code", columnNames = {"code"})
    },
    indexes = {
        @Index(name = "idx_security_rule_sets_code", columnList = "code"),
        @Index(name = "idx_security_rule_sets_scope", columnList = "enforcement_scope")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class SecurityRuleSet extends BaseEntity {

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(length = 1000)
    private String description;

    @Column(name = "enforcement_scope", length = 60, nullable = false)
    private String enforcementScope;

    @Column(name = "rule_count", nullable = false)
    @Builder.Default
    private Integer ruleCount = 0;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "published_by", length = 120)
    private String publishedBy;

    @Column(name = "published_at")
    private java.time.LocalDateTime publishedAt;
}
