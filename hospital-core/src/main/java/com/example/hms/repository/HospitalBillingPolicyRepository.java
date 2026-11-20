package com.example.hms.repository;

import com.example.hms.model.HospitalBillingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Policy rules 1 and 6 (V345): a hospital's own billing choices; read by the hospital, at most one row. */
public interface HospitalBillingPolicyRepository extends JpaRepository<HospitalBillingPolicy, UUID> {

    Optional<HospitalBillingPolicy> findByHospitalId(UUID hospitalId);
}
