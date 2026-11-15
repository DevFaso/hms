package com.example.hms.repository.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.model.pharmacy.PharmacyClaim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PharmacyClaimRepository extends JpaRepository<PharmacyClaim, UUID> {

    Page<PharmacyClaim> findByDispenseId(UUID dispenseId, Pageable pageable);

    Page<PharmacyClaim> findByPatientId(UUID patientId, Pageable pageable);

    Page<PharmacyClaim> findByDispenseIdAndHospitalId(UUID dispenseId, UUID hospitalId, Pageable pageable);

    Page<PharmacyClaim> findByPatientIdAndHospitalId(UUID patientId, UUID hospitalId, Pageable pageable);

    Page<PharmacyClaim> findByHospitalId(UUID hospitalId, Pageable pageable);

    Page<PharmacyClaim> findByHospitalIdAndClaimStatus(UUID hospitalId,
                                                        PharmacyClaimStatus status,
                                                        Pageable pageable);

    List<PharmacyClaim> findByHospitalIdAndClaimStatusIn(UUID hospitalId,
                                                         List<PharmacyClaimStatus> statuses);

    /**
     * QA D1613: whether a dispense already carries a live claim (any status but
     * REJECTED). A rejected claim is the one a resubmission replaces.
     */
    boolean existsByDispenseIdAndClaimStatusNot(UUID dispenseId, PharmacyClaimStatus status);

    /** QA D1613: what the live (non-REJECTED) claims of one dispense ask of the payer. */
    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(c.amount), 0) FROM PharmacyClaim c "
         + "WHERE c.dispense.id = :dispenseId AND c.claimStatus <> com.example.hms.enums.PharmacyClaimStatus.REJECTED")
    java.math.BigDecimal sumLiveForDispense(
            @org.springframework.data.repository.query.Param("dispenseId") UUID dispenseId);
}
