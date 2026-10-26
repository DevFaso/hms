package com.example.hms.repository;

import com.example.hms.model.platform.SubscriptionPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    Optional<SubscriptionPlan> findByTierCodeIgnoreCase(String tierCode);

    List<SubscriptionPlan> findByActiveTrue();

    /**
     * QA D850: the plan row, locked for the rest of the transaction. Deactivating
     * a plan (count ACTIVE subscriptions, then flip the flag) and assigning it
     * (read the flag, then insert an ACTIVE subscription) both take this lock,
     * so they serialize and an ACTIVE subscription can never land on a plan
     * that was deactivated at the same moment.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM SubscriptionPlan p WHERE p.id = :id")
    Optional<SubscriptionPlan> findByIdForUpdate(@Param("id") UUID id);
}
