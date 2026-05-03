package com.example.hms.repository;

import com.example.hms.model.platform.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    Optional<SubscriptionPlan> findByTierCodeIgnoreCase(String tierCode);

    List<SubscriptionPlan> findByActiveTrue();
}
