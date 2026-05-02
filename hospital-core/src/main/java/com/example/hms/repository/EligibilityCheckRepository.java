package com.example.hms.repository;

import com.example.hms.enums.EligibilityCheckType;
import com.example.hms.enums.EligibilityScheme;
import com.example.hms.model.insurance.EligibilityCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EligibilityCheckRepository extends JpaRepository<EligibilityCheck, UUID> {

    Page<EligibilityCheck> findByPatient_IdOrderByRequestedAtDesc(UUID patientId, Pageable pageable);

    Optional<EligibilityCheck> findFirstByPatient_IdAndSchemeAndCheckTypeOrderByRequestedAtDesc(
        UUID patientId, EligibilityScheme scheme, EligibilityCheckType checkType);
}
