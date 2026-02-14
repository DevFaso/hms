package com.example.hms.model.security;

import com.example.hms.enums.SecurityPolicyApprovalStatus;
import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "security_policy_approvals",
    schema = "governance",
    indexes = {
        @Index(name = "idx_security_policy_approvals_status", columnList = "status"),
        @Index(name = "idx_security_policy_approvals_required_by", columnList = "required_by")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class SecurityPolicyApproval extends BaseEntity {

    @Column(name = "policy_name", nullable = false, length = 160)
    private String policyName;

    @Column(name = "change_type", length = 80)
    private String changeType;

    @Column(name = "requested_by", length = 120)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SecurityPolicyApprovalStatus status;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "required_by")
    private LocalDateTime requiredBy;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Column(name = "baseline_version", length = 80)
    private String baselineVersion;

    @Column(name = "severity", length = 40)
    private String severity;
}
