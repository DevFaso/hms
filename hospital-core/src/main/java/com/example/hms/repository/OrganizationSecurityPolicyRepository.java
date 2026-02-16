package com.example.hms.repository;

import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.enums.SecurityPolicyType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationSecurityPolicyRepository extends JpaRepository<OrganizationSecurityPolicy, UUID> {

    @EntityGraph(attributePaths = {"organization", "rules"})
    Optional<OrganizationSecurityPolicy> findByOrganizationIdAndCode(UUID organizationId, String code);

    @EntityGraph(attributePaths = {"organization", "rules"})
    List<OrganizationSecurityPolicy> findByOrganizationIdAndActiveTrue(UUID organizationId);

    @EntityGraph(attributePaths = {"organization", "rules"})
    List<OrganizationSecurityPolicy> findByOrganizationId(UUID organizationId);

    List<OrganizationSecurityPolicy> findByOrganizationIdAndPolicyTypeAndActiveTrue(UUID organizationId, SecurityPolicyType policyType);

    @Query("SELECT osp FROM OrganizationSecurityPolicy osp JOIN FETCH osp.rules r " +
           "WHERE osp.organization.id = :organizationId AND osp.active = true AND r.active = true " +
           "ORDER BY osp.priority DESC, r.priority DESC")
    List<OrganizationSecurityPolicy> findActiveByOrganizationWithRules(@Param("organizationId") UUID organizationId);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);
}
