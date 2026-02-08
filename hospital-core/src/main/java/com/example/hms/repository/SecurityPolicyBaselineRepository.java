package com.example.hms.repository;

import com.example.hms.model.security.SecurityPolicyBaseline;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityPolicyBaselineRepository extends JpaRepository<SecurityPolicyBaseline, UUID> {

    Optional<SecurityPolicyBaseline> findFirstByOrderByCreatedAtDesc();
}
