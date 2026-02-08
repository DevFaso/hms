package com.example.hms.repository;

import com.example.hms.model.security.SecurityRuleSet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityRuleSetRepository extends JpaRepository<SecurityRuleSet, UUID> {

    Optional<SecurityRuleSet> findFirstByOrderByCreatedAtDesc();

    Optional<SecurityRuleSet> findByCodeIgnoreCase(String code);
}
