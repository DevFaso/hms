package com.example.hms.repository;

import com.example.hms.model.platform.OrganizationSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationSubscriptionRepository
    extends JpaRepository<OrganizationSubscription, UUID> {

    Optional<OrganizationSubscription> findByOrganizationIdAndStatus(
        UUID organizationId, OrganizationSubscription.Status status);

    List<OrganizationSubscription> findByOrganizationId(UUID organizationId);

    List<OrganizationSubscription> findByPlanId(UUID planId);
}
