package com.example.hms.repository;

import com.example.hms.model.Organization;
import com.example.hms.enums.OrganizationType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByCode(String code);

    Optional<Organization> findByNameIgnoreCase(String name);

    List<Organization> findByActiveTrue();

    @Query("SELECT o FROM Organization o WHERE o.active = true AND o.type = :type")
    List<Organization> findActiveByType(@Param("type") OrganizationType type);

    boolean existsByCode(String code);

    @Query("SELECT o FROM Organization o JOIN FETCH o.securityPolicies sp WHERE o.id = :organizationId AND sp.active = true")
    Optional<Organization> findByIdWithActiveSecurityPolicies(@Param("organizationId") UUID organizationId);

    @EntityGraph(attributePaths = "hospitals")
    @Query("SELECT o FROM Organization o WHERE o.id = :organizationId")
    Optional<Organization> findByIdWithHospitals(@Param("organizationId") UUID organizationId);
}
