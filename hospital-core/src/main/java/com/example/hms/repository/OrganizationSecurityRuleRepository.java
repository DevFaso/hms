package com.example.hms.repository;

import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.enums.SecurityRuleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationSecurityRuleRepository extends JpaRepository<OrganizationSecurityRule, UUID> {

    Optional<OrganizationSecurityRule> findBySecurityPolicyIdAndCode(UUID securityPolicyId, String code);

    List<OrganizationSecurityRule> findBySecurityPolicyIdAndActiveTrue(UUID securityPolicyId);

    List<OrganizationSecurityRule> findBySecurityPolicyIdAndRuleTypeAndActiveTrue(UUID securityPolicyId, SecurityRuleType ruleType);

    @Query("SELECT osr FROM OrganizationSecurityRule osr " +
           "JOIN osr.securityPolicy osp " +
           "WHERE osp.organization.id = :organizationId " +
           "AND osr.active = true AND osp.active = true " +
           "AND osr.ruleType = :ruleType " +
           "ORDER BY osp.priority DESC, osr.priority DESC")
    List<OrganizationSecurityRule> findActiveByOrganizationAndRuleType(
        @Param("organizationId") UUID organizationId,
        @Param("ruleType") SecurityRuleType ruleType);

    @Query("SELECT osr FROM OrganizationSecurityRule osr " +
           "JOIN osr.securityPolicy osp " +
           "WHERE osp.organization.id = :organizationId " +
           "AND osr.active = true AND osp.active = true " +
           "ORDER BY osp.priority DESC, osr.priority DESC")
    List<OrganizationSecurityRule> findActiveByOrganization(@Param("organizationId") UUID organizationId);

    boolean existsBySecurityPolicyIdAndCode(UUID securityPolicyId, String code);
}
