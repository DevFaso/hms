package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.PharmacyClaim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PharmacyClaimRepository extends JpaRepository<PharmacyClaim, UUID> {

    Page<PharmacyClaim> findByDispenseId(UUID dispenseId, Pageable pageable);

    Page<PharmacyClaim> findByPatientId(UUID patientId, Pageable pageable);
}
